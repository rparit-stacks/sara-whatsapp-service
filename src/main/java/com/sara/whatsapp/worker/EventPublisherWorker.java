package com.sara.whatsapp.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.OutboundEvent;
import com.sara.whatsapp.security.Hashing;
import com.sara.whatsapp.service.ClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Delivers outbound events to client callback URLs. Each request is signed:
 * X-Signature = sha256= HMAC(secret, timestamp + "." + body). Failures retry with backoff.
 */
@Component
public class EventPublisherWorker {

    private static final Logger log = LoggerFactory.getLogger(EventPublisherWorker.class);
    private static final int LEASE_SECONDS = 60;
    private static final int PER_TICK = 20;

    private final MongoTemplate mongo;
    private final ClientService clients;
    private final ObjectMapper mapper;
    private final WhatsAppProperties.Events config;
    private final RestClient http;

    public EventPublisherWorker(MongoTemplate mongo, ClientService clients, ObjectMapper mapper, WhatsAppProperties props) {
        this.mongo = mongo;
        this.clients = clients;
        this.mapper = mapper;
        this.config = props.getEvents();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** With no events waiting, the database is asked again every five seconds; stuck events are freed every 30. */
    private static final long IDLE_MS = 5000;
    private static final long LEASE_CHECK_MS = 30_000;
    private volatile long pollAfter = 0;
    private volatile long leaseCheckAfter = 0;

    @Scheduled(fixedDelay = 2000)
    public void tick() {
        long clock = System.currentTimeMillis();
        if (clock < pollAfter) {
            return;
        }
        Instant now = Instant.now();
        if (clock >= leaseCheckAfter) {
            leaseCheckAfter = clock + LEASE_CHECK_MS;
            mongo.updateMulti(
                    new Query(Criteria.where("status").is(OutboundEvent.Status.SENDING).and("lockedUntil").lt(now)),
                    new Update().set("status", OutboundEvent.Status.PENDING).set("nextAttemptAt", now).unset("lockedUntil"),
                    OutboundEvent.class);
        }

        for (int i = 0; i < PER_TICK; i++) {
            OutboundEvent event = claim();
            if (event == null) {
                if (i == 0) {
                    pollAfter = System.currentTimeMillis() + IDLE_MS;
                }
                return;
            }
            deliver(event);
        }
    }

    private OutboundEvent claim() {
        Instant now = Instant.now();
        Query query = new Query(Criteria.where("status").is(OutboundEvent.Status.PENDING).and("nextAttemptAt").lte(now))
                .with(org.springframework.data.domain.Sort.by("createdAt"));
        Update update = new Update()
                .set("status", OutboundEvent.Status.SENDING)
                .set("lockedUntil", now.plusSeconds(LEASE_SECONDS))
                .inc("attempts", 1);
        return mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), OutboundEvent.class);
    }

    private void deliver(OutboundEvent event) {
        Optional<ApiClient> client = clients.find(event.getClientId())
                .filter(c -> c.isActive() && c.getCallbackUrl() != null && !c.getCallbackUrl().isBlank());
        if (client.isEmpty()) {
            finish(event, OutboundEvent.Status.FAILED, "client has no active callback URL");
            return;
        }
        try {
            byte[] body = mapper.writeValueAsBytes(envelope(event));
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            ResponseEntity<Void> response = http.post()
                    .uri(client.get().getCallbackUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Event-Id", event.getId())
                    .header("X-Event-Type", event.getType())
                    .header("X-Timestamp", timestamp)
                    .header("X-Signature", sign(clients.callbackSecret(client.get()), timestamp, body))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> true, (request, resp) -> { })
                    .toBodilessEntity();
            if (response.getStatusCode().is2xxSuccessful()) {
                finish(event, OutboundEvent.Status.DELIVERED, null);
            } else {
                retryOrFail(event, "HTTP " + response.getStatusCode().value());
            }
        } catch (Exception e) {
            retryOrFail(event, e.getClass().getSimpleName());
        }
    }

    private void retryOrFail(OutboundEvent event, String error) {
        if (event.getAttempts() >= config.getMaxAttempts()) {
            log.warn("Event {} gave up after {} attempts: {}", event.getId(), event.getAttempts(), error);
            finish(event, OutboundEvent.Status.FAILED, error);
            return;
        }
        long base = (long) config.getBaseBackoffSeconds() << Math.min(Math.max(event.getAttempts() - 1, 0), 20);
        long delay = Math.min(base, config.getMaxBackoffSeconds());
        mongo.updateFirst(new Query(Criteria.where("_id").is(event.getId())),
                new Update().set("status", OutboundEvent.Status.PENDING)
                        .set("nextAttemptAt", Instant.now().plusSeconds(delay))
                        .set("lastError", error).unset("lockedUntil"),
                OutboundEvent.class);
    }

    private void finish(OutboundEvent event, OutboundEvent.Status status, String error) {
        Update update = new Update().set("status", status).set("lastError", error).unset("lockedUntil");
        if (status == OutboundEvent.Status.DELIVERED) {
            update.set("deliveredAt", Instant.now());
        }
        mongo.updateFirst(new Query(Criteria.where("_id").is(event.getId())), update, OutboundEvent.class);
    }

    private static Map<String, Object> envelope(OutboundEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", event.getId());
        out.put("type", event.getType());
        out.put("createdAt", event.getCreatedAt());
        out.put("data", event.getData());
        return out;
    }

    /** The value of the X-Signature header. The receiver recomputes it from the timestamp header and the raw body. */
    public static String sign(String secret, String timestamp, byte[] body) {
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        return "sha256=" + Hashing.hmacSha256Hex(secret, signed);
    }
}
