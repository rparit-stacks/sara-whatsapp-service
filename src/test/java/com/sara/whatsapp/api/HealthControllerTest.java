package com.sara.whatsapp.api;

import com.mongodb.client.MongoDatabase;
import com.sara.whatsapp.config.WhatsAppProperties;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MongoDatabase db = mock(MongoDatabase.class);
    private final WhatsAppProperties props = new WhatsAppProperties();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(ResponseEntity<Map<String, Object>> r) {
        return (Map<String, Object>) r.getBody().get("meta");
    }

    @Test
    void upWhenTheDatabaseAnswers() {
        when(mongo.getDb()).thenReturn(db);
        props.getMeta().setAccessToken("t");
        props.getMeta().setWabaId("w");
        props.getMeta().setPhoneNumberId("p");
        props.getMeta().setAppSecret("s");
        props.getMeta().setWebhookVerifyToken("v");

        ResponseEntity<Map<String, Object>> r = new HealthController(mongo, props).health();

        assertEquals(200, r.getStatusCode().value());
        assertEquals("UP", r.getBody().get("status"));
        assertTrue((Boolean) meta(r).get("ready"));
        assertFalse(r.getBody().toString().contains("t,"), "no secret values in the answer");
    }

    @Test
    void downWhenTheDatabaseDoesNotAnswer() {
        when(mongo.getDb()).thenReturn(db);
        when(db.runCommand(any(Document.class))).thenThrow(new RuntimeException("connection refused"));

        ResponseEntity<Map<String, Object>> r = new HealthController(mongo, props).health();

        assertEquals(503, r.getStatusCode().value());
        assertEquals("DOWN", r.getBody().get("status"));
        assertFalse((Boolean) meta(r).get("ready"), "nothing is configured in this test");
    }
}
