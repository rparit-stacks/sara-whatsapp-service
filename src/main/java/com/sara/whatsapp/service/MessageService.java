package com.sara.whatsapp.service;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.api.dto.SendMessageRequest;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.Broadcast;
import com.sara.whatsapp.domain.ConsentStatus;
import com.sara.whatsapp.domain.EventBinding;
import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.MessageStatus;
import com.sara.whatsapp.domain.Priority;
import com.sara.whatsapp.domain.Template;
import com.sara.whatsapp.repo.EventBindingRepository;
import com.sara.whatsapp.repo.MessageRepository;
import com.sara.whatsapp.repo.TemplateRepository;
import com.sara.whatsapp.util.Phone;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Accepts messages into the queue and reads the log. */
@Service
public class MessageService {

    /** Template statuses Meta uses that mean the template cannot be sent right now. */
    private static final Set<String> PAUSED_STATUSES = Set.of("PAUSED", "DISABLED");
    private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Za-z0-9_]{1,60}$");
    static final int MAX_VARIABLE_LENGTH = 1024;

    public record QueueResult(Message message, boolean duplicate) {
    }

    private final MessageRepository messages;
    private final TemplateRepository templates;
    private final EventBindingRepository bindings;
    private final StatusService status;
    private final ContactService contacts;
    private final MongoTemplate mongo;
    private final WhatsAppProperties props;

    public MessageService(MessageRepository messages, TemplateRepository templates, EventBindingRepository bindings,
                          StatusService status, ContactService contacts, MongoTemplate mongo, WhatsAppProperties props) {
        this.messages = messages;
        this.templates = templates;
        this.bindings = bindings;
        this.status = status;
        this.contacts = contacts;
        this.mongo = mongo;
        this.props = props;
    }

    public QueueResult queue(ApiClient client, SendMessageRequest request) {
        Optional<Message> existing = messages.findByClientIdAndClientMessageId(client.getId(), request.clientMessageId());
        if (existing.isPresent()) {
            return new QueueResult(existing.get(), true);
        }

        EventBinding binding = null;
        String templateKey = blankToNull(request.templateKey());
        if (templateKey == null) {
            String event = blankToNull(request.event());
            if (event == null) {
                throw ApiException.badRequest("VALIDATION_FAILED", "Either templateKey or event is required");
            }
            binding = bindings.findByEvent(event)
                    .filter(EventBinding::isActive)
                    .orElseThrow(() -> ApiException.unprocessable("EVENT_NOT_BOUND", "No active binding for event '" + event + "'"));
            templateKey = binding.getTemplateKey();
        }
        final String resolvedKey = templateKey;
        Template template = templates.findByKey(resolvedKey)
                .orElseThrow(() -> ApiException.unprocessable("TEMPLATE_MISSING", "Unknown template '" + resolvedKey + "'"));
        validateParameters(template, request.parameters());

        Instant now = Instant.now();
        Message message = new Message();
        message.setClientId(client.getId());
        message.setClientMessageId(request.clientMessageId());
        message.setEvent(blankToNull(request.event()));
        message.setTemplateKey(template.getKey());
        message.setCategory(template.getCategory());
        message.setParameters(request.parameters() == null ? new MessageParameters() : request.parameters());
        message.setPriority(request.priority() != null ? request.priority()
                : binding != null && binding.getDefaultPriority() != null ? binding.getDefaultPriority() : Priority.NORMAL);
        message.setExpiresAt(expiry(request, binding, now));
        message.setNextAttemptAt(now);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);

        Optional<String> number = Phone.normalize(request.to(), props.getDefaultCountryCode());
        message.setTo(number.orElse(request.to().trim()));
        number.ifPresent(n -> message.setRecipientCountry(Phone.countryCode(n)));

        String skipReason = number.isEmpty() ? "INVALID_NUMBER" : templateSkipReason(template);
        if (skipReason == null) {
            skipReason = consentSkipReason(template, contacts.consentOf(number.get()));
        }
        if (skipReason != null) {
            // No next attempt time, so the worker never picks it up before it is marked SKIPPED.
            message.setNextAttemptAt(null);
        }

        Message saved;
        try {
            saved = messages.save(message);
        } catch (DuplicateKeyException e) {
            return new QueueResult(messages.findByClientIdAndClientMessageId(client.getId(), request.clientMessageId())
                    .orElseThrow(() -> e), true);
        }
        if (skipReason != null) {
            status.skip(saved.getId(), skipReason);
            saved = messages.findById(saved.getId()).orElse(saved);
        }
        return new QueueResult(saved, false);
    }

    /**
     * Creates one message of a broadcast. Safe to repeat: the id is built from the broadcast and the
     * recipient, so the same recipient never gets a second message from the same broadcast.
     */
    public void queueForBroadcast(Broadcast broadcast, Template template, String rawPhone, MessageParameters params) {
        Instant now = Instant.now();
        Optional<String> number = Phone.normalize(rawPhone, props.getDefaultCountryCode());
        String phone = number.orElse(rawPhone == null ? "" : rawPhone.trim());

        Message message = new Message();
        message.setClientId(broadcast.getClientId());
        message.setClientMessageId("bc-" + broadcast.getId() + "-" + phone);
        message.setTemplateKey(template.getKey());
        message.setCategory(template.getCategory());
        message.setParameters(params == null ? new MessageParameters() : params);
        message.setPriority(Priority.BULK);
        message.setBroadcastId(broadcast.getId());
        message.setTo(phone);
        number.ifPresent(n -> message.setRecipientCountry(Phone.countryCode(n)));
        message.setNextAttemptAt(now);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);

        String skipReason = number.isEmpty() ? "INVALID_NUMBER" : templateSkipReason(template);
        if (skipReason == null && number.isPresent()) {
            skipReason = consentSkipReason(template, contacts.consentOf(number.get()));
        }
        if (skipReason != null) {
            message.setNextAttemptAt(null);
        }
        Message saved;
        try {
            saved = messages.save(message);
        } catch (DuplicateKeyException e) {
            return;
        }
        if (skipReason != null) {
            status.skip(saved.getId(), skipReason);
        }
    }

    public Optional<Message> find(ApiClient client, String id) {
        return messages.findById(id).filter(m -> client.getId().equals(m.getClientId()));
    }

    public record Filter(String status, String to, String event, String templateKey, Instant from, Instant until,
                         String q, String sort, String dir) {
        public Filter(String status, String to, String event, String templateKey, Instant from, Instant until) {
            this(status, to, event, templateKey, from, until, null, null, null);
        }
    }

    private static final java.util.Set<String> SORTABLE = java.util.Set.of(
            "createdAt", "sentAt", "deliveredAt", "readAt", "status", "to", "templateKey", "attempts");

    public org.springframework.data.domain.Page<Message> list(ApiClient client, Filter filter, int page, int size) {
        List<Criteria> criteria = new ArrayList<>();
        criteria.add(Criteria.where("clientId").is(client.getId()));
        if (filter.status() != null) {
            try {
                criteria.add(Criteria.where("status").is(MessageStatus.valueOf(filter.status().toUpperCase())));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("VALIDATION_FAILED", "Unknown status " + filter.status());
            }
        }
        if (filter.to() != null) {
            criteria.add(Criteria.where("to").is(Phone.normalize(filter.to(), props.getDefaultCountryCode()).orElse(filter.to())));
        }
        if (filter.event() != null) criteria.add(Criteria.where("event").is(filter.event()));
        if (filter.templateKey() != null) criteria.add(Criteria.where("templateKey").is(filter.templateKey()));
        if (filter.from() != null) criteria.add(Criteria.where("createdAt").gte(filter.from()));
        if (filter.until() != null) criteria.add(Criteria.where("createdAt").lt(filter.until()));

        if (filter.q() != null && !filter.q().isBlank()) {
            // Free-text search over phone, template, event, client message id and error text.
            String text = java.util.regex.Pattern.quote(filter.q().trim());
            String digits = filter.q().replaceAll("\\D", "");
            List<Criteria> any = new ArrayList<>();
            any.add(Criteria.where("templateKey").regex(text, "i"));
            any.add(Criteria.where("event").regex(text, "i"));
            any.add(Criteria.where("clientMessageId").regex(text, "i"));
            any.add(Criteria.where("errorMessage").regex(text, "i"));
            any.add(Criteria.where("errorCode").regex(text, "i"));
            if (digits.length() >= 3) {
                any.add(Criteria.where("to").regex(java.util.regex.Pattern.quote(digits)));
            }
            criteria.add(new Criteria().orOperator(any.toArray(new Criteria[0])));
        }

        Query query = new Query(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        long total = mongo.count(query, Message.class);
        String sortField = filter.sort() != null && SORTABLE.contains(filter.sort()) ? filter.sort() : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(filter.dir()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by(direction, sortField).and(Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Message> content = mongo.find(query.with(pageable), Message.class);
        return new org.springframework.data.domain.PageImpl<>(content, pageable, total);
    }

    /** Puts a failed message back in the queue. */
    public Message retry(ApiClient client, String id) {
        Message message = find(client, id).orElseThrow(() -> ApiException.notFound("Message not found"));
        if (message.getStatus() != MessageStatus.FAILED) {
            throw ApiException.unprocessable("NOT_RETRYABLE", "Only a FAILED message can be retried");
        }
        Instant now = Instant.now();
        mongo.updateFirst(
                new Query(Criteria.where("_id").is(id).and("status").is(MessageStatus.FAILED)),
                new Update().set("status", MessageStatus.QUEUED).set("attempts", 0).set("nextAttemptAt", now)
                        .set("updatedAt", now).unset("errorCode").unset("errorMessage"),
                Message.class);
        return messages.findById(id).orElse(message);
    }

    void validateParameters(Template template, MessageParameters params) {
        MessageParameters p = params == null ? new MessageParameters() : params;
        if (p.getVariables() != null) {
            for (String name : p.getVariables().keySet()) {
                if (!VARIABLE_NAME.matcher(name).matches()) {
                    throw ApiException.unprocessable("INVALID_PARAMETER", "Variable names may use letters, digits and '_': " + name);
                }
                String text = p.getVariables().get(name);
                if (text != null && text.length() > MAX_VARIABLE_LENGTH) {
                    throw ApiException.unprocessable("PARAMETER_TOO_LONG", "Variable '" + name + "' is longer than " + MAX_VARIABLE_LENGTH + " characters");
                }
            }
        }
        if (template.getBodyParameters() != null) {
            for (String required : template.getBodyParameters()) {
                String value = p.getVariables() == null ? null : p.getVariables().get(required);
                if (value == null || value.isBlank()) {
                    throw ApiException.unprocessable("MISSING_PARAMETER", "Template '" + template.getKey() + "' needs variable '" + required + "'");
                }
            }
        }
        String headerType = template.getHeaderType();
        if (headerType != null && !"TEXT".equals(headerType)) {
            MessageParameters.Header header = p.getHeader();
            if (header == null || header.getValue() == null || header.getValue().isBlank()) {
                throw ApiException.unprocessable("MISSING_HEADER", "Template '" + template.getKey() + "' needs a " + headerType.toLowerCase() + " header");
            }
        }
        if (p.getButtons() != null) {
            for (MessageParameters.Button button : p.getButtons()) {
                if (button.getValue() == null || button.getValue().isBlank() || button.getSubType() == null) {
                    throw ApiException.unprocessable("INVALID_PARAMETER", "Each button needs subType and value");
                }
            }
        }
        TemplateSendRules.apply(template, p);
    }

    /** A template whose status is known and not usable is skipped. An unknown status is allowed: Meta decides. */
    private static String templateSkipReason(Template template) {
        String status = template.getStatus();
        if (status == null || status.isBlank() || "APPROVED".equals(status)) {
            return null;
        }
        return PAUSED_STATUSES.contains(status) ? "TEMPLATE_PAUSED" : "TEMPLATE_NOT_APPROVED";
    }

    /**
     * Marketing templates need an opt-in. A contact who opted out gets nothing except
     * authentication messages, which the person asked for by logging in.
     */
    static String consentSkipReason(Template template, ConsentStatus consent) {
        String category = template.getCategory() == null ? "" : template.getCategory().toUpperCase();
        if ("MARKETING".equals(category) && consent != ConsentStatus.OPTED_IN) {
            return "NO_CONSENT";
        }
        if (consent == ConsentStatus.OPTED_OUT && !"AUTHENTICATION".equals(category)) {
            return "NO_CONSENT";
        }
        return null;
    }

    private static Instant expiry(SendMessageRequest request, EventBinding binding, Instant now) {
        if (request.expiresAt() != null) {
            return request.expiresAt();
        }
        if (binding != null && binding.getDefaultTtlSeconds() != null) {
            return now.plusSeconds(binding.getDefaultTtlSeconds());
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
