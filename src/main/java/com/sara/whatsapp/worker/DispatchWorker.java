package com.sara.whatsapp.worker;

import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.MessageStatus;
import com.sara.whatsapp.domain.Template;
import com.sara.whatsapp.provider.ProviderRegistry;
import com.sara.whatsapp.provider.SendResult;
import com.sara.whatsapp.provider.WhatsAppProvider;
import com.sara.whatsapp.repo.TemplateRepository;
import com.sara.whatsapp.service.StatusService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Takes queued messages and hands them to the active provider. A message is claimed with one
 * atomic update, so several workers (or instances) never send the same message twice.
 */
@Component
public class DispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(DispatchWorker.class);

    private final MongoTemplate mongo;
    private final TemplateRepository templates;
    private final ProviderRegistry providers;
    private final StatusService status;
    private final WhatsAppProperties.Send config;
    private final WorkSignal signal;

    private final String workerId = UUID.randomUUID().toString();
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DispatchWorker(MongoTemplate mongo, TemplateRepository templates, ProviderRegistry providers,
                          StatusService status, WhatsAppProperties props, WorkSignal signal) {
        this.mongo = mongo;
        this.templates = templates;
        this.providers = providers;
        this.status = status;
        this.config = props.getSend();
        this.signal = signal;
        this.permits = new Semaphore(Math.max(1, config.getConcurrency()));
    }

    /** Safety net only: how long the worker sleeps with nothing signalled, in case work was saved by another instance or before a restart. */
    private static final long SAFETY_MS = 30_000;
    private volatile boolean running = true;

    @PostConstruct
    void start() {
        Thread t = new Thread(this::loop, "dispatch-worker");
        t.setDaemon(true);
        t.start();
    }

    /** Sleeps until a message is queued (or a retry falls due), sends what is waiting, and goes back to sleep. */
    private void loop() {
        while (running) {
            try {
                if (!tick()) {
                    signal.await(WorkSignal.Kind.DISPATCH, SAFETY_MS);
                }
            } catch (Exception e) {
                log.error("Dispatch loop failed, trying again in a moment", e);
                signal.await(WorkSignal.Kind.DISPATCH, 2000);
            }
        }
    }

    /** Claims and starts as many waiting messages as there are free sending slots. True when it started any and should look again. */
    boolean tick() {
        boolean started = false;
        while (permits.tryAcquire()) {
            Message message = claim();
            if (message == null) {
                permits.release();
                return started;
            }
            started = true;
            executor.submit(() -> {
                try {
                    process(message);
                } finally {
                    permits.release();
                    signal.wake(WorkSignal.Kind.DISPATCH); // a slot is free: look for the next message
                }
            });
        }
        return false; // every slot is busy; a finishing message wakes this worker
    }

    /** Oldest message in the highest lane that is due and not expired. */
    Message claim() {
        Instant now = Instant.now();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(MessageStatus.QUEUED),
                Criteria.where("nextAttemptAt").lte(now),
                new Criteria().orOperator(Criteria.where("expiresAt").is(null), Criteria.where("expiresAt").gt(now))))
                .with(Sort.by(Sort.Order.asc("priorityRank"), Sort.Order.asc("createdAt")));
        Update update = new Update()
                .set("status", MessageStatus.SENDING)
                .set("lockedBy", workerId)
                .set("lockedUntil", now.plusSeconds(config.getLeaseSeconds()))
                .set("updatedAt", now)
                .inc("attempts", 1);
        return mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), Message.class);
    }

    void process(Message message) {
        try {
            Template template = templates.findByKey(message.getTemplateKey()).orElse(null);
            if (template == null) {
                status.markFailed(message, "TEMPLATE_MISSING", "Template '" + message.getTemplateKey() + "' no longer exists");
                return;
            }
            if (message.getParameters() == null) {
                message.setParameters(new MessageParameters());
            }
            WhatsAppProvider provider = providers.active();
            SendResult result = provider.send(message, template);
            if (result.success()) {
                status.markSent(message, provider.name(), result.providerMessageId());
            } else if (result.retryable() && message.getAttempts() < config.getMaxAttempts()) {
                status.scheduleRetry(message, Instant.now().plusSeconds(backoffSeconds(message.getAttempts())),
                        result.errorCode(), result.errorMessage());
            } else {
                status.markFailed(message, result.errorCode(), result.errorMessage());
            }
        } catch (Exception e) {
            log.error("Dispatch failed for message {}", message.getId(), e);
            status.scheduleRetry(message, Instant.now().plusSeconds(backoffSeconds(message.getAttempts())),
                    "INTERNAL", e.getClass().getSimpleName());
        }
    }

    /** Exponential backoff with up to 20 percent jitter. */
    long backoffSeconds(int attempts) {
        long base = (long) config.getBaseBackoffSeconds() << Math.min(Math.max(attempts - 1, 0), 20);
        long capped = Math.min(base, config.getMaxBackoffSeconds());
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Math.max(1, Math.round(capped * jitter));
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        running = false;
        signal.wake(WorkSignal.Kind.DISPATCH);
        executor.shutdown();
        executor.awaitTermination(20, TimeUnit.SECONDS);
    }
}
