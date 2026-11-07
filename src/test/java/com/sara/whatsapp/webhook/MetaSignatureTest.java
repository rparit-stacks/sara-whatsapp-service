package com.sara.whatsapp.webhook;

import com.sara.whatsapp.security.Hashing;
import com.sara.whatsapp.domain.MessageStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaSignatureTest {

    private static final String SECRET = "app-secret";
    private static final byte[] BODY = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void validSignatureIsAccepted() {
        String header = "sha256=" + Hashing.hmacSha256Hex(SECRET, BODY);
        assertTrue(MetaSignature.verify(BODY, header, SECRET));
    }

    @Test
    void tamperedBodyIsRejected() {
        String header = "sha256=" + Hashing.hmacSha256Hex(SECRET, BODY);
        assertFalse(MetaSignature.verify("{\"object\":\"other\"}".getBytes(StandardCharsets.UTF_8), header, SECRET));
    }

    @Test
    void wrongSecretMissingHeaderOrMissingSecretIsRejected() {
        String header = "sha256=" + Hashing.hmacSha256Hex(SECRET, BODY);
        assertFalse(MetaSignature.verify(BODY, header, "other-secret"));
        assertFalse(MetaSignature.verify(BODY, null, SECRET));
        assertFalse(MetaSignature.verify(BODY, "abc", SECRET));
        assertFalse(MetaSignature.verify(BODY, header, ""));
    }

    @Test
    void metaStatusesMapToOurStatuses() {
        assertEquals(MessageStatus.SENT, MetaWebhookController.map("sent"));
        assertEquals(MessageStatus.DELIVERED, MetaWebhookController.map("delivered"));
        assertEquals(MessageStatus.READ, MetaWebhookController.map("read"));
        assertEquals(MessageStatus.FAILED, MetaWebhookController.map("failed"));
        assertNull(MetaWebhookController.map("something_new"));
    }
}
