package com.sara.whatsapp.worker;

import com.sara.whatsapp.security.Hashing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EventSigningTest {

    @Test
    void signatureCoversTimestampAndBody() {
        byte[] body = "{\"id\":\"e1\"}".getBytes(StandardCharsets.UTF_8);
        String signature = EventPublisherWorker.sign("secret", "1700000000", body);

        assertEquals("sha256=" + Hashing.hmacSha256Hex("secret", "1700000000.{\"id\":\"e1\"}".getBytes(StandardCharsets.UTF_8)), signature);
        assertNotEquals(signature, EventPublisherWorker.sign("secret", "1700000001", body));
        assertNotEquals(signature, EventPublisherWorker.sign("other", "1700000000", body));
    }
}
