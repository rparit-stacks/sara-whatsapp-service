package com.sara.whatsapp.api;

import com.sara.whatsapp.config.WhatsAppProperties;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Open health check for deploys and monitors (no key needed, no secrets in the answer). It says
 * whether the service is up, whether the database answers, and which Meta settings are present.
 * Answers 503 when the database is down.
 */
@RestController
public class HealthController {

    private final MongoTemplate mongo;
    private final WhatsAppProperties props;

    public HealthController(MongoTemplate mongo, WhatsAppProperties props) {
        this.mongo = mongo;
        this.props = props;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean databaseUp;
        try {
            mongo.getDb().runCommand(new Document("ping", 1));
            databaseUp = true;
        } catch (RuntimeException e) {
            databaseUp = false;
        }

        WhatsAppProperties.Meta meta = props.getMeta();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("accessToken", present(meta.getAccessToken()));
        settings.put("businessAccountId", present(meta.getWabaId()));
        settings.put("phoneNumberId", present(meta.getPhoneNumberId()));
        settings.put("appSecret", present(meta.getAppSecret()));
        settings.put("webhookVerifyToken", present(meta.getWebhookVerifyToken()));
        boolean metaReady = settings.values().stream().allMatch(Boolean.TRUE::equals);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", databaseUp ? "UP" : "DOWN");
        body.put("service", "sara-whatsapp-service");
        body.put("time", Instant.now().toString());
        body.put("database", databaseUp ? "UP" : "DOWN");
        body.put("meta", Map.of("ready", metaReady, "settings", settings));
        return databaseUp ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
