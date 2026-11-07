package com.sara.whatsapp.provider.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.provider.SendResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaErrorsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rateLimitErrorIsRetryable() {
        SendResult result = MetaErrors.classify(400,
                "{\"error\":{\"message\":\"Too many messages\",\"code\":130429}}", mapper);
        assertTrue(result.retryable());
        assertEquals("130429", result.errorCode());
    }

    @Test
    void undeliverableNumberIsPermanent() {
        SendResult result = MetaErrors.classify(400,
                "{\"error\":{\"message\":\"Message undeliverable\",\"code\":131026,\"error_data\":{\"details\":\"not on WhatsApp\"}}}", mapper);
        assertFalse(result.retryable());
        assertEquals("131026", result.errorCode());
        assertTrue(result.errorMessage().contains("not on WhatsApp"));
    }

    @Test
    void serverErrorsAreRetryable() {
        assertTrue(MetaErrors.classify(503, "<html>down</html>", mapper).retryable());
        assertTrue(MetaErrors.classify(429, null, mapper).retryable());
    }

    @Test
    void invalidTokenIsNotRetried() {
        assertFalse(MetaErrors.classify(401, "{\"error\":{\"message\":\"Invalid OAuth access token\",\"code\":190}}", mapper).retryable());
    }
}
