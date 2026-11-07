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
    private final Set<String> optOutKeywords;

    public InboundService(InboundMessageRepository repository, ContactService contacts, ClientService clients,
                          OutboundEventService outbound, WhatsAppProperties props) {
        this.repository = repository;
        this.contacts = contacts;
        this.clients = clients;
        this.outbound = outbound;
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
            notifyClients(message);
        }
    }

    public Page<InboundMessage> list(String from, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by("receivedAt").descending());
        return from == null || from.isBlank() ? repository.findAll(pageable) : repository.findByFrom(from.replaceAll("\\D", ""), pageable);
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
