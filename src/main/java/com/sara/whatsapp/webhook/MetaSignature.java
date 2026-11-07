package com.sara.whatsapp.webhook;

import com.sara.whatsapp.security.Hashing;

/** Checks Meta's X-Hub-Signature-256 header: sha256= plus the HMAC of the raw body with the app secret. */
public final class MetaSignature {

    private static final String PREFIX = "sha256=";

    private MetaSignature() {
    }

    public static boolean verify(byte[] rawBody, String header, String appSecret) {
        if (rawBody == null || header == null || appSecret == null || appSecret.isBlank() || !header.startsWith(PREFIX)) {
            return false;
        }
        String expected = Hashing.hmacSha256Hex(appSecret, rawBody);
        return Hashing.constantTimeEquals(expected, header.substring(PREFIX.length()).trim().toLowerCase());
    }
}
