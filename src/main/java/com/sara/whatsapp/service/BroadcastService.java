package com.sara.whatsapp.service;

import com.sara.whatsapp.worker.WorkSignal;
import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.Broadcast;
import com.sara.whatsapp.domain.ConsentStatus;
import com.sara.whatsapp.domain.Contact;
import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.MessageStatus;
import com.sara.whatsapp.domain.Template;
import com.sara.whatsapp.repo.BroadcastRepository;
import com.sara.whatsapp.repo.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Broadcasts. Starting one does not send anything at once: a worker turns the audience into
 * messages in small chunks (lane BULK), staying under the daily limit, and each message then goes
 * through the normal queue with consent and template checks.
 */
@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);
    public static final int MAX_RECIPIENTS = 5000;

    public record CreateRequest(String name, Broadcast.Kind kind, String templateKey, MessageParameters parameters,
                                List<String> tags, Boolean allContacts, List<Broadcast.Recipient> recipients,
                                Instant scheduledAt) {
    }

    private final BroadcastRepository broadcasts;
    private final TemplateRepository templates;
    private final MessageService messageService;
    private final OutboundEventService outbound;
    private final MongoTemplate mongo;
    private final WhatsAppProperties.Broadcast config;
    private final WorkSignal signal;

    public BroadcastService(BroadcastRepository broadcasts, TemplateRepository templates, MessageService messageService,
                            OutboundEventService outbound, MongoTemplate mongo, WhatsAppProperties props, WorkSignal signal) {
        this.broadcasts = broadcasts;
        this.templates = templates;
        this.messageService = messageService;
        this.outbound = outbound;
        this.mongo = mongo;
        this.config = props.getBroadcast();
        this.signal = signal;
    }

    public Broadcast create(ApiClient client, CreateRequest request) {
        Instant now = Instant.now();
        Broadcast broadcast = new Broadcast();
        broadcast.setClientId(client.getId());
        broadcast.setCreatedBy(client.getName());
        broadcast.setCreatedAt(now);
        apply(broadcast, request);
        Broadcast saved = broadcasts.save(broadcast);
        wakeForSchedule(saved);
        return saved;
    }

    /** A scheduled broadcast needs the worker awake when it falls due. */
    private void wakeForSchedule(Broadcast b) {
        if (b.getStatus() == Broadcast.Status.SCHEDULED && b.getScheduledAt() != null) {
            signal.wakeFor(WorkSignal.Kind.BROADCAST, b.getScheduledAt());
        }
    }

    /** Changes a broadcast that has not started yet (DRAFT or SCHEDULED). */
    public Broadcast update(ApiClient client, String id, CreateRequest request) {
        Broadcast broadcast = get(client, id);
        if (broadcast.getStatus() != Broadcast.Status.DRAFT && broadcast.getStatus() != Broadcast.Status.SCHEDULED) {
            throw ApiException.unprocessable("NOT_EDITABLE", "Only a DRAFT or SCHEDULED broadcast can be edited");
        }
        apply(broadcast, request);
        Broadcast saved = broadcasts.save(broadcast);
        wakeForSchedule(saved);
        return saved;
    }

    /** Validates the request and writes it onto the broadcast. */
    private void apply(Broadcast broadcast, CreateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "name is required");
        }
        Template template = templates.findByKey(request.templateKey() == null ? "" : request.templateKey())
                .orElseThrow(() -> ApiException.unprocessable("TEMPLATE_MISSING", "Unknown template '" + request.templateKey() + "'"));

        List<Broadcast.Recipient> recipients = request.recipients() == null ? List.of() : request.recipients();
        List<String> tags = request.tags() == null ? List.of() : request.tags().stream().filter(t -> t != null && !t.isBlank()).map(String::trim).toList();
        boolean allContacts = Boolean.TRUE.equals(request.allContacts());
        boolean hasList = !recipients.isEmpty();
        boolean hasSegment = !tags.isEmpty() || allContacts;
        if (hasList == hasSegment) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Give either recipients, or tags / allContacts, not both and not neither");
        }
        if (recipients.size() > MAX_RECIPIENTS) {
            throw ApiException.badRequest("VALIDATION_FAILED", "At most " + MAX_RECIPIENTS + " recipients in a list; use tags for larger audiences");
        }
        if (request.scheduledAt() != null && request.scheduledAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("VALIDATION_FAILED", "scheduledAt must be in the future");
        }

        MessageParameters shared = request.parameters() == null ? new MessageParameters() : request.parameters();
        if (hasList) {
            for (int i = 0; i < recipients.size(); i++) {
                Broadcast.Recipient r = recipients.get(i);
                if (r.getTo() == null || r.getTo().isBlank()) {
                    throw ApiException.badRequest("VALIDATION_FAILED", "recipients[" + i + "].to is required");
                }
                messageService.validateParameters(template, merged(shared, r.getVariables()));
            }
        } else {
            messageService.validateParameters(template, shared);
        }

        broadcast.setName(request.name().trim());
        broadcast.setKind(request.kind() == null ? Broadcast.Kind.CAMPAIGN : request.kind());
        broadcast.setTemplateKey(template.getKey());
        broadcast.setParameters(shared);
        broadcast.setTags(tags);
        broadcast.setAllContacts(allContacts);
        broadcast.setRecipients(recipients);
        broadcast.setScheduledAt(request.scheduledAt());
        broadcast.setStatus(request.scheduledAt() != null ? Broadcast.Status.SCHEDULED : Broadcast.Status.DRAFT);
        broadcast.setUpdatedAt(Instant.now());
    }

    /** Stops turning the audience into messages. Messages already queued still go out; cancel to stop those. */
    public Broadcast pause(ApiClient client, String id) {
        return move(client, id, Broadcast.Status.RUNNING, Broadcast.Status.PAUSED, "NOT_PAUSABLE", "Only a RUNNING broadcast can be paused");
    }

    public Broadcast resume(ApiClient client, String id) {
        return move(client, id, Broadcast.Status.PAUSED, Broadcast.Status.RUNNING, "NOT_RESUMABLE", "Only a PAUSED broadcast can be resumed");
    }

    private Broadcast move(ApiClient client, String id, Broadcast.Status from, Broadcast.Status to, String code, String message) {
        Broadcast b = get(client, id);
        long changed = mongo.updateFirst(
                new Query(Criteria.where("_id").is(b.getId()).and("status").is(from)),
                new Update().set("status", to).set("updatedAt", Instant.now()),
                Broadcast.class).getModifiedCount();
        if (changed == 0) {
            throw ApiException.unprocessable(code, message);
        }
        if (to == Broadcast.Status.RUNNING) {
            signal.wake(WorkSignal.Kind.BROADCAST);
        }
        return broadcasts.findById(id).orElse(b);
    }

    public Broadcast get(ApiClient client, String id) {
        return broadcasts.findById(id).filter(b -> client.getId().equals(b.getClientId()))
                .orElseThrow(() -> ApiException.notFound("Broadcast not found"));
    }

    public org.springframework.data.domain.Page<Broadcast> list(ApiClient client, int page, int size) {
        Query query = new Query(Criteria.where("clientId").is(client.getId()));
        long total = mongo.count(query, Broadcast.class);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by("createdAt").descending());
        return new PageImpl<>(mongo.find(query.with(pageable), Broadcast.class), pageable, total);
    }

    /** DRAFT or SCHEDULED to RUNNING now. */
    public Broadcast start(ApiClient client, String id) {
        Broadcast b = get(client, id);
        Instant now = Instant.now();
        long changed = mongo.updateFirst(
                new Query(Criteria.where("_id").is(b.getId()).and("status").in(Broadcast.Status.DRAFT, Broadcast.Status.SCHEDULED)),
                new Update().set("status", Broadcast.Status.RUNNING).set("startedAt", now).set("updatedAt", now),
                Broadcast.class).getModifiedCount();
        if (changed == 0) {
            throw ApiException.unprocessable("NOT_STARTABLE", "Only a DRAFT or SCHEDULED broadcast can be started");
        }
        signal.wake(WorkSignal.Kind.BROADCAST);
        return broadcasts.findById(id).orElse(b);
    }

    /** Stops a broadcast and skips the messages of it that have not been sent yet. */
    public Broadcast cancel(ApiClient client, String id) {
        Broadcast b = get(client, id);
        Instant now = Instant.now();
        long changed = mongo.updateFirst(
                new Query(Criteria.where("_id").is(b.getId()).and("status")
                        .in(Broadcast.Status.DRAFT, Broadcast.Status.SCHEDULED, Broadcast.Status.RUNNING, Broadcast.Status.PAUSED)),
                new Update().set("status", Broadcast.Status.CANCELLED).set("completedAt", now).set("updatedAt", now),
                Broadcast.class).getModifiedCount();
        if (changed == 0) {
            throw ApiException.unprocessable("NOT_CANCELLABLE", "This broadcast has already finished");
        }
        mongo.updateMulti(
                new Query(Criteria.where("broadcastId").is(id).and("status").is(MessageStatus.QUEUED)),
                new Update().set("status", MessageStatus.SKIPPED).set("skipReason", "BROADCAST_CANCELLED").set("updatedAt", now)
                        .unset("lockedBy").unset("lockedUntil"),
                Message.class);
        return broadcasts.findById(id).orElse(b);
    }

    /** Messages of a broadcast, newest first. */
    public org.springframework.data.domain.Page<Message> recipients(ApiClient client, String id, String status, int page, int size) {
        get(client, id);
        List<Criteria> criteria = new ArrayList<>();
        criteria.add(Criteria.where("broadcastId").is(id));
        if (status != null && !status.isBlank()) {
            try {
                criteria.add(Criteria.where("status").is(MessageStatus.valueOf(status.toUpperCase())));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("VALIDATION_FAILED", "Unknown status " + status);
            }
        }
        Query query = new Query(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        long total = mongo.count(query, Message.class);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by("createdAt").descending());
        return new PageImpl<>(mongo.find(query.with(pageable), Message.class), pageable, total);
    }

    /** Count of the broadcast's messages by status. */
    public Map<String, Long> counts(String broadcastId) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("broadcastId").is(broadcastId)),
                Aggregation.group("status").count().as("n"));
        Map<String, Long> out = new LinkedHashMap<>();
        for (org.bson.Document row : mongo.aggregate(aggregation, Message.class, org.bson.Document.class)) {
            out.put(String.valueOf(row.get("_id")), ((Number) row.get("n")).longValue());
        }
        return out;
    }

    // ---- used by the worker ----

    /** SCHEDULED broadcasts that are due start running. */
    public void startDue() {
        Instant now = Instant.now();
        mongo.updateMulti(
                new Query(Criteria.where("status").is(Broadcast.Status.SCHEDULED).and("scheduledAt").lte(now)),
                new Update().set("status", Broadcast.Status.RUNNING).set("startedAt", now).set("updatedAt", now),
                Broadcast.class);
    }

    /** Takes one RUNNING broadcast that no other worker holds. */
    public Broadcast claimRunning() {
        Instant now = Instant.now();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(Broadcast.Status.RUNNING),
                new Criteria().orOperator(Criteria.where("lockedUntil").is(null), Criteria.where("lockedUntil").lt(now))));
        return mongo.findAndModify(query, new Update().set("lockedUntil", now.plusSeconds(60)),
                FindAndModifyOptions.options().returnNew(true), Broadcast.class);
    }

    public void release(String id) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)), new Update().unset("lockedUntil"), Broadcast.class);
    }

    /** Turns the next chunk of the audience into messages, within the daily limit. */
    public void expandNext(Broadcast b) {
        Template template = templates.findByKey(b.getTemplateKey()).orElse(null);
        if (template == null) {
            log.warn("Broadcast {} stopped: template {} no longer exists", b.getId(), b.getTemplateKey());
            mongo.updateFirst(new Query(Criteria.where("_id").is(b.getId())),
                    new Update().set("status", Broadcast.Status.CANCELLED).set("completedAt", Instant.now()), Broadcast.class);
            return;
        }
        int chunk = Math.min(config.getChunkSize(), remainingBudget());
        if (chunk <= 0) {
            return;
        }

        int created = 0;
        Update progress = new Update().set("updatedAt", Instant.now());
        if (!b.getRecipients().isEmpty()) {
            int from = b.getRecipientCursor();
            int to = Math.min(from + chunk, b.getRecipients().size());
            for (int i = from; i < to; i++) {
                Broadcast.Recipient r = b.getRecipients().get(i);
                messageService.queueForBroadcast(b, template, r.getTo(), merged(b.getParameters(), r.getVariables()));
                created++;
            }
            progress.set("recipientCursor", to).set("expansionDone", to >= b.getRecipients().size());
        } else {
            List<Contact> contacts = mongo.find(audienceQuery(b).limit(chunk), Contact.class);
            for (Contact contact : contacts) {
                messageService.queueForBroadcast(b, template, contact.getPhone(), b.getParameters());
                created++;
            }
            if (!contacts.isEmpty()) {
                progress.set("cursor", contacts.get(contacts.size() - 1).getId());
            }
            progress.set("expansionDone", contacts.size() < chunk);
        }
        progress.inc("totalCreated", created);
        mongo.updateFirst(new Query(Criteria.where("_id").is(b.getId())), progress, Broadcast.class);
    }

    /** A broadcast is COMPLETED when every message has been created and none is waiting any more. */
    public void completeIfDone(String id) {
        Broadcast b = broadcasts.findById(id).orElse(null);
        if (b == null || b.getStatus() != Broadcast.Status.RUNNING || !b.isExpansionDone()) {
            return;
        }
        long open = mongo.count(new Query(Criteria.where("broadcastId").is(id)
                .and("status").in(MessageStatus.QUEUED, MessageStatus.SENDING)), Message.class);
        if (open > 0) {
            return;
        }
        Instant now = Instant.now();
        long changed = mongo.updateFirst(
                new Query(Criteria.where("_id").is(id).and("status").is(Broadcast.Status.RUNNING)),
                new Update().set("status", Broadcast.Status.COMPLETED).set("completedAt", now).set("updatedAt", now),
                Broadcast.class).getModifiedCount();
        if (changed > 0) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("broadcastId", id);
            data.put("name", b.getName());
            data.put("counts", counts(id));
            outbound.publish(b.getClientId(), "broadcast.completed", data);
        }
    }

    /** Opted-in contacts that carry a tag (or all of them), after the cursor. */
    private Query audienceQuery(Broadcast b) {
        List<Criteria> criteria = new ArrayList<>();
        criteria.add(Criteria.where("consentStatus").is(ConsentStatus.OPTED_IN));
        if (!b.isAllContacts() && !b.getTags().isEmpty()) {
            criteria.add(Criteria.where("tags").in(b.getTags()));
        }
        if (b.getCursor() != null) {
            criteria.add(Criteria.where("id").gt(b.getCursor()));
        }
        return new Query(new Criteria().andOperator(criteria.toArray(new Criteria[0]))).with(Sort.by("id"));
    }

    /** How many more recipients may start within the rolling 24 hours. */
    int remainingBudget() {
        Instant since = Instant.now().minusSeconds(24 * 3600L);
        long used = mongo.count(new Query(Criteria.where("broadcastId").exists(true)
                .and("createdAt").gte(since).and("status").ne(MessageStatus.SKIPPED)), Message.class);
        return (int) Math.max(0, config.getDailyLimit() - used);
    }

    /** Shared parameters with one recipient's own variables laid over them. */
    static MessageParameters merged(MessageParameters shared, Map<String, String> own) {
        MessageParameters out = new MessageParameters();
        MessageParameters base = shared == null ? new MessageParameters() : shared;
        out.setHeader(base.getHeader());
        out.setButtons(base.getButtons());
        Map<String, String> variables = new LinkedHashMap<>();
        if (base.getVariables() != null) variables.putAll(base.getVariables());
        if (own != null) variables.putAll(own);
        out.setVariables(variables);
        return out;
    }
}
