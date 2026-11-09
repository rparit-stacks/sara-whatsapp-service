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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
    private final WorkSignal signal;
    private final RestClient http;

    public EventPublisherWorker(MongoTemplate mongo, ClientService clients, ObjectMapper mapper, WhatsAppProperties props, WorkSignal signal) {
        this.mongo = mongo;
        this.clients = clients;
        this.mapper = mapper;
        this.config = props.getEvents();
        this.signal = signal;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Safety net sleep with nothing signalled; stuck events are freed on the same rhythm. */
    private static final long SAFETY_MS = 30_000;
    private volatile boolean running = true;

    @PostConstruct
    void start() {
        Thread t = new Thread(this::loop, "event-publisher");
        t.setDaemon(true);
        t.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        signal.wake(WorkSignal.Kind.EVENTS);
    }

    /** Sleeps until an event is published (or a retry falls due), delivers what is waiting, and goes back to sleep. */
    private void loop() {
        while (running) {
            try {
                if (!tick()) {
                    signal.await(WorkSignal.Kind.EVENTS, SAFETY_MS);
                }
            } catch (Exception e) {
                log.error("Event publisher loop failed, trying again in a moment", e);
                signal.await(WorkSignal.Kind.EVENTS, 5_000);
            }
        }
    }

    /** Frees events whose worker died, then delivers up to a batch. True when it delivered any and should look again. */
    boolean tick() {
        Instant now = Instant.now();
        mongo.updateMulti(
                new Query(Criteria.where("status").is(OutboundEvent.Status.SENDING).and("lockedUntil").lt(now)),
                new Update().set("status", OutboundEvent.Status.PENDING).set("nextAttemptAt", now).unset("lockedUntil"),
                OutboundEvent.class);

        int delivered = 0;
        for (int i = 0; i < PER_TICK; i++) {
            OutboundEvent event = claim();
            if (event == null) {
                break;
            }
            deliver(event);
            delivered++;
        }
        return delivered == PER_TICK;
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
        Instant next = Instant.now().plusSeconds(delay);
        mongo.updateFirst(new Query(Criteria.where("_id").is(event.getId())),
                new Update().set("status", OutboundEvent.Status.PENDING)
                        .set("nextAttemptAt", next)
                        .set("lastError", error).unset("lockedUntil"),
                OutboundEvent.class);
        signal.wakeFor(WorkSignal.Kind.EVENTS, next);
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
