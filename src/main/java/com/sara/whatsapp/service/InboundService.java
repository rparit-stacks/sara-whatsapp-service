package com.sara.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.ConsentStatus;
import com.sara.whatsapp.domain.InboundMessage;
import com.sara.whatsapp.repo.InboundMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Messages from customers. They are stored, a reply that is an opt-out keyword removes the
 * sender's consent at once, and every client with a callback URL is told.
 */
@Service
public class InboundService {

    private static final Logger log = LoggerFactory.getLogger(InboundService.class);

    private final InboundMessageRepository repository;
    private final ContactService contacts;
    private final ClientService clients;
    private final OutboundEventService outbound;
    private final AutoReplyService autoReply;
    private final com.sara.whatsapp.bot.BotConversationService conversations;
    private final com.sara.whatsapp.bot.BotEngine bot;
    private final MongoTemplate mongo;
    private final Set<String> optOutKeywords;

    public InboundService(InboundMessageRepository repository, ContactService contacts, ClientService clients,
                          OutboundEventService outbound, AutoReplyService autoReply, MongoTemplate mongo, com.sara.whatsapp.bot.BotConversationService conversations, com.sara.whatsapp.bot.BotEngine bot, WhatsAppProperties props) {
        this.repository = repository;
        this.contacts = contacts;
        this.clients = clients;
        this.outbound = outbound;
        this.autoReply = autoReply;
        this.conversations = conversations;
        this.bot = bot;
        this.mongo = mongo;
        this.optOutKeywords = props.getOptOutKeywords().stream()
                .map(InboundService::canonical).collect(Collectors.toSet());
    }

    /** Handles the {@code value} of a Meta "messages" webhook change that carries incoming messages. */
    public void handle(JsonNode value) {
        JsonNode names = value.path("contacts");
        for (JsonNode node : value.path("messages")) {
            String id = node.path("id").asText("");
            String from = node.path("from").asText("");
            if (id.isBlank() || from.isBlank()) {
                continue;
            }
            String name = null;
            for (JsonNode contact : names) {
                if (from.equals(contact.path("wa_id").asText())) {
                    name = contact.path("profile").path("name").asText(null);
                }
            }
            String type = node.path("type").asText("unknown");
            String text = text(node, type);
            boolean optOut = isOptOut(text);

            InboundMessage message = new InboundMessage();
            message.setProviderMessageId(id);
            message.setFrom(from);
            message.setName(name);
            message.setType(type);
            message.setText(text);
            message.setOptedOut(optOut);
            message.setSentAt(parseTime(node.path("timestamp").asText(null)));
            message.setReceivedAt(Instant.now());
            try {
                repository.save(message);
            } catch (DuplicateKeyException e) {
                continue; // Meta repeated the webhook
            }

            if (optOut) {
                try {
                    contacts.setConsent(from, ConsentStatus.OPTED_OUT, "whatsapp-reply", name);
                } catch (RuntimeException e) {
                    log.warn("Could not record the opt-out of {}: {}", from, e.getMessage());
                }
            }
            // Opens (or extends) the 24-hour window in which the bot may reply with free-form messages.
            boolean newCustomer = false;
            try {
                newCustomer = conversations.onInbound(from, message.getReceivedAt());
            } catch (RuntimeException e) {
                log.warn("Could not record the conversation for {}: {}", from, e.getMessage());
            }
            notifyClients(message);
            if (!optOut) {
                // The bot answers while its licence is live and a flow is published; otherwise the older no-reply notice applies.
                if (bot.isActive()) {
                    bot.submit(from, name, text, newCustomer);
                } else {
                    autoReply.replyTo(from);
                }
            }
        }
    }

    public record InboundFilter(String from, String q, String type, Boolean optedOut, Instant since, Instant until,
                                String sort, String dir) {
    }

    public Page<InboundMessage> list(InboundFilter f, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        java.util.List<Criteria> all = new java.util.ArrayList<>();
        if (f.from() != null && !f.from().isBlank()) {
            String digits = f.from().replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                all.add(Criteria.where("from").regex(java.util.regex.Pattern.quote(digits)));
            }
        }
        if (f.q() != null && !f.q().isBlank()) {
            String needle = java.util.regex.Pattern.quote(f.q().trim());
            all.add(new Criteria().orOperator(
                    Criteria.where("name").regex(needle, "i"),
                    Criteria.where("text").regex(needle, "i"),
                    Criteria.where("from").regex(needle)));
        }
        if (f.type() != null && !f.type().isBlank()) {
            all.add(Criteria.where("type").is(f.type()));
        }
        if (f.optedOut() != null) {
            all.add(Criteria.where("optedOut").is(f.optedOut()));
        }
        if (f.since() != null || f.until() != null) {
            Criteria range = Criteria.where("receivedAt");
            if (f.since() != null) range = range.gte(f.since());
            if (f.until() != null) range = range.lt(f.until());
            all.add(range);
        }
        Query query = new Query();
        if (!all.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(all.toArray(new Criteria[0])));
        }
        long total = mongo.count(query, InboundMessage.class);
        String field = switch (f.sort() == null ? "" : f.sort()) {
            case "name" -> "name";
            case "from" -> "from";
            case "text" -> "text";
            default -> "receivedAt";
        };
        Sort.Direction direction = "asc".equalsIgnoreCase(f.dir()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        query.with(Sort.by(direction, field).and(Sort.by(Sort.Direction.DESC, "receivedAt"))).with(pageable);
        return new org.springframework.data.domain.PageImpl<>(mongo.find(query, InboundMessage.class), pageable, total);
    }

    boolean isOptOut(String text) {
        return text != null && optOutKeywords.contains(canonical(text));
    }

    private void notifyClients(InboundMessage m) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", m.getProviderMessageId());
        data.put("from", m.getFrom());
        data.put("name", m.getName());
        data.put("type", m.getType());
        data.put("text", m.getText());
        data.put("optedOut", m.isOptedOut());
        data.put("sentAt", m.getSentAt());
        for (ApiClient client : clients.list()) {
            if (client.isActive()) {
                outbound.publish(client.getId(), "message.received", data);
            }
        }
    }

    private static String text(JsonNode node, String type) {
        return switch (type) {
            case "text" -> node.path("text").path("body").asText(null);
            case "button" -> node.path("button").path("text").asText(null);
            case "interactive" -> {
                JsonNode interactive = node.path("interactive");
                String reply = interactive.path("button_reply").path("title").asText(null);
                yield reply != null ? reply : interactive.path("list_reply").path("title").asText(null);
            }
            default -> null;
        };
    }

    private static String canonical(String s) {
        return s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static Instant parseTime(String epochSeconds) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds));
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
