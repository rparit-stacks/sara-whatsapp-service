package com.sara.whatsapp.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretBoxTest {

    private static String key(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    @Test
    void roundTripAndRandomIv() {
        SecretBox box = new SecretBox(key(32));
        String a = box.encrypt("callback-secret");
        assertNotEquals("callback-secret", a);
        assertNotEquals(a, box.encrypt("callback-secret"));
        assertEquals("callback-secret", box.decrypt(a));
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String encrypted = new SecretBox(key(32)).encrypt("x");
        SecretBox other = new SecretBox(Base64.getEncoder().encodeToString(new byte[16]));
        assertThrows(IllegalStateException.class, () -> other.decrypt(encrypted));
    }

    @Test
    void missingKeyRefusesToEncrypt() {
        SecretBox box = new SecretBox("");
        assertFalse(box.isConfigured());
        assertThrows(IllegalStateException.class, () -> box.encrypt("x"));
    }

    @Test
    void hashingIsStableAndConstantTimeEqualsWorks() {
        assertEquals(Hashing.sha256Hex("abc"), Hashing.sha256Hex("abc"));
        assertFalse(Hashing.constantTimeEquals("a", "b"));
        assertFalse(Hashing.constantTimeEquals(null, "b"));
    }
}
