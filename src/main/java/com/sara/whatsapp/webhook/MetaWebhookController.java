package com.sara.whatsapp.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.MessageStatus;
import com.sara.whatsapp.security.Hashing;
import com.sara.whatsapp.service.InboundService;
import com.sara.whatsapp.service.StatusService;
import com.sara.whatsapp.service.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Receives Meta's webhooks. Every POST must carry a valid signature. A 200 tells Meta the event
 * was handled. Meta retries anything else for days, so processing must be repeatable: status
 * changes are conditional updates, and an unknown message is simply ignored.
 */
@RestController
@RequestMapping("/webhooks/meta")
public class MetaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWebhookController.class);

    private final WhatsAppProperties.Meta meta;
    private final ObjectMapper mapper;
    private final StatusService statusService;
    private final TemplateService templateService;
    private final InboundService inboundService;

    public MetaWebhookController(WhatsAppProperties props, ObjectMapper mapper, StatusService statusService,
                                 TemplateService templateService, InboundService inboundService) {
        this.meta = props.getMeta();
        this.mapper = mapper;
        this.statusService = statusService;
        this.templateService = templateService;
        this.inboundService = inboundService;
    }

    /** Meta's one-time handshake when the webhook is configured. */
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && challenge != null
                && Hashing.constantTimeEquals(meta.getWebhookVerifyToken(), token)
                && !meta.getWebhookVerifyToken().isBlank()) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (meta.getAppSecret() == null || meta.getAppSecret().isBlank()) {
            log.warn("Meta webhook rejected: META_APP_SECRET is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!MetaSignature.verify(rawBody, signature, meta.getAppSecret())) {
            log.warn("Meta webhook rejected: invalid signature");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            JsonNode root = mapper.readTree(rawBody);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    String field = change.path("field").asText("");
                    JsonNode value = change.path("value");
                    if ("messages".equals(field)) {
                        handleStatuses(value.path("statuses"));
                        if (value.path("messages").isArray() && !value.path("messages").isEmpty()) {
                            inboundService.handle(value);
                        }
                    } else if ("message_template_status_update".equals(field)) {
                        templateService.applyStatusUpdate(
                                value.path("message_template_id").asText(null),
                                value.path("message_template_name").asText(null),
                                value.path("message_template_language").asText(null),
                                value.path("event").asText(null));
                    }
                }
            }
            return ResponseEntity.ok().build();
        } catch (java.io.IOException e) {
            log.warn("Meta webhook body is not valid JSON");
            return ResponseEntity.badRequest().build();
        }
    }

    private void handleStatuses(JsonNode statuses) {
        for (JsonNode status : statuses) {
            String id = status.path("id").asText("");
            MessageStatus next = map(status.path("status").asText(""));
            if (id.isBlank() || next == null) {
                continue;
            }
            Instant at = parseTime(status.path("timestamp").asText(null));
            JsonNode error = status.path("errors").path(0);
            String code = error.has("code") ? error.path("code").asText() : null;
            String detail = error.path("error_data").path("details").asText(error.path("title").asText(null));
            statusService.applyProviderStatus(id, next, at, code, detail);
        }
    }

    static MessageStatus map(String metaStatus) {
        return switch (metaStatus) {
            case "sent" -> MessageStatus.SENT;
            case "delivered" -> MessageStatus.DELIVERED;
            case "read", "played" -> MessageStatus.READ;
            case "failed" -> MessageStatus.FAILED;
            default -> null;
        };
    }

    private static Instant parseTime(String epochSeconds) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds));
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
