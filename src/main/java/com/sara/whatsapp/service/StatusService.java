package com.sara.whatsapp.service;

import com.mongodb.client.result.UpdateResult;
import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageEvent;
import com.sara.whatsapp.domain.MessageStatus;
import com.sara.whatsapp.repo.MessageEventRepository;
import com.sara.whatsapp.repo.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one place that changes a message's status. Every change is a conditional update, so a
 * stale or out-of-order update cannot move a message backwards. Each real change is recorded
 * as a message event and queued as an outbound event for the client.
 */
@Service
public class StatusService {

    private static final Logger log = LoggerFactory.getLogger(StatusService.class);

    private final MongoTemplate mongo;
    private final MessageRepository messages;
    private final MessageEventRepository events;
    private final OutboundEventService outbound;

    public StatusService(MongoTemplate mongo, MessageRepository messages, MessageEventRepository events,
                         OutboundEventService outbound) {
        this.mongo = mongo;
        this.messages = messages;
        this.events = events;
        this.outbound = outbound;
    }

    /** Worker: the provider accepted the message. Only the worker holding the lock may do this. */
    public boolean markSent(Message m, String provider, String providerMessageId) {
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", MessageStatus.SENT)
                .set("provider", provider)
                .set("providerMessageId", providerMessageId)
                .set("sentAt", now)
                .set("updatedAt", now)
                .unset("lockedBy").unset("lockedUntil").unset("errorCode").unset("errorMessage");
        return applyOwned(m, update, MessageStatus.SENT, now, null, null);
    }

    /** Worker: a permanent error, or retries used up. */
    public boolean markFailed(Message m, String code, String message) {
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", MessageStatus.FAILED)
                .set("errorCode", code)
                .set("errorMessage", message)
                .set("updatedAt", now)
                .unset("lockedBy").unset("lockedUntil");
        return applyOwned(m, update, MessageStatus.FAILED, now, code, message);
    }

    /** Worker: a temporary error. Back to the queue, to be tried again at {@code next}. */
    public void scheduleRetry(Message m, Instant next, String code, String message) {
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", MessageStatus.QUEUED)
                .set("nextAttemptAt", next)
                .set("errorCode", code)
                .set("errorMessage", message)
                .set("updatedAt", now)
                .unset("lockedBy").unset("lockedUntil");
        UpdateResult result = mongo.updateFirst(ownedQuery(m), update, Message.class);
        if (result.getModifiedCount() > 0) {
            recordEvent(m.getId(), "RETRY_SCHEDULED", now, code, message);
        }
    }

    /** A message that will not be sent: bad number, template not usable, expired, over a limit. */
    public boolean skip(String messageId, String reason) {
        Instant now = Instant.now();
        Query query = new Query(Criteria.where("_id").is(messageId).and("status").is(MessageStatus.QUEUED));
        Update update = new Update()
                .set("status", MessageStatus.SKIPPED)
                .set("skipReason", reason)
                .set("updatedAt", now)
                .unset("lockedBy").unset("lockedUntil");
        UpdateResult result = mongo.updateFirst(query, update, Message.class);
        if (result.getModifiedCount() == 0) {
            return false;
        }
        recordEvent(messageId, "SKIPPED", now, reason, null);
        publish(messageId, "message.skipped");
        return true;
    }

    /** Provider webhook: a status for a message the provider accepted earlier. */
    public void applyProviderStatus(String providerMessageId, MessageStatus next, Instant at, String errorCode, String detail) {
        Message message = messages.findByProviderMessageId(providerMessageId).orElse(null);
        if (message == null) {
            log.info("Status {} for unknown provider message {} ignored", next, providerMessageId);
            return;
        }
        // Meta may repeat a status (or send "sent" after this service already logged it). Log each status once.
        if (!events.existsByMessageIdAndStatus(message.getId(), next.name())) {
            recordEvent(message.getId(), next.name(), at, errorCode, detail);
        }

        Query query = new Query(Criteria.where("_id").is(message.getId())
                .and("status").in(MessageStatus.allowedBefore(next)));
        Update update = new Update().set("status", next).set("updatedAt", Instant.now());
        switch (next) {
            case SENT -> update.set("sentAt", at);
            case DELIVERED -> update.set("deliveredAt", at);
            case READ -> update.set("readAt", at);
            case FAILED -> update.set("errorCode", errorCode).set("errorMessage", detail);
            default -> { }
        }
        if (mongo.updateFirst(query, update, Message.class).getModifiedCount() == 0) {
            return;
        }
        if (next == MessageStatus.READ) {
            // "delivered" can be skipped when a message is read at once. Fill the gap.
            mongo.updateFirst(new Query(Criteria.where("_id").is(message.getId()).and("deliveredAt").is(null)),
                    new Update().set("deliveredAt", at), Message.class);
        }
        publish(message.getId(), "message." + next.name().toLowerCase());
    }

    public void recordEvent(String messageId, String status, Instant at, String errorCode, String detail) {
        MessageEvent event = new MessageEvent();
        event.setMessageId(messageId);
        event.setStatus(status);
        event.setAt(at);
        event.setErrorCode(errorCode);
        event.setDetail(detail);
        events.save(event);
    }

    private boolean applyOwned(Message m, Update update, MessageStatus status, Instant now, String code, String detail) {
        UpdateResult result = mongo.updateFirst(ownedQuery(m), update, Message.class);
        if (result.getModifiedCount() == 0) {
            log.warn("Message {} was no longer owned by this worker, {} not applied", m.getId(), status);
            return false;
        }
        recordEvent(m.getId(), status.name(), now, code, detail);
        publish(m.getId(), "message." + status.name().toLowerCase());
        return true;
    }

    private static Query ownedQuery(Message m) {
        return new Query(Criteria.where("_id").is(m.getId())
                .and("status").is(MessageStatus.SENDING)
                .and("lockedBy").is(m.getLockedBy()));
    }

    private void publish(String messageId, String type) {
        messages.findById(messageId).ifPresent(m -> outbound.publish(m.getClientId(), type, eventData(m)));
    }

    static Map<String, Object> eventData(Message m) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", m.getId());
        data.put("clientMessageId", m.getClientMessageId());
        data.put("event", m.getEvent());
        data.put("templateKey", m.getTemplateKey());
        data.put("to", m.getTo());
        data.put("status", m.getStatus().name());
        data.put("skipReason", m.getSkipReason());
        data.put("provider", m.getProvider());
        data.put("providerMessageId", m.getProviderMessageId());
        data.put("errorCode", m.getErrorCode());
        data.put("errorMessage", m.getErrorMessage());
        data.put("sentAt", m.getSentAt());
        data.put("deliveredAt", m.getDeliveredAt());
        data.put("readAt", m.getReadAt());
        data.put("updatedAt", m.getUpdatedAt());
        return data;
    }
}
