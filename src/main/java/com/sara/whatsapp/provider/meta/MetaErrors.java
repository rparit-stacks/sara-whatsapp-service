package com.sara.whatsapp.provider.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.provider.SendResult;

import java.util.Set;

/** Turns a Meta error response into a retryable or permanent result. */
public final class MetaErrors {

    /**
     * Error codes that are temporary: rate limits and Meta-side hiccups. Everything else (invalid
     * number, template problems, bad token) will not succeed on its own and is not retried.
     */
    static final Set<Integer> RETRYABLE_CODES = Set.of(2, 4, 17, 32, 613, 80007, 130429, 131000, 131016, 131056);

    private MetaErrors() {
    }

    public static SendResult classify(int httpStatus, String body, ObjectMapper mapper) {
        String code = String.valueOf(httpStatus);
        String message = "HTTP " + httpStatus;
        boolean retryable = httpStatus == 429 || httpStatus >= 500;
        try {
            JsonNode error = mapper.readTree(body == null ? "{}" : body).path("error");
            if (error.has("code")) {
                int metaCode = error.path("code").asInt();
                code = String.valueOf(metaCode);
                retryable = retryable || RETRYABLE_CODES.contains(metaCode);
            }
            String detail = error.path("error_data").path("details").asText("");
            String text = error.path("message").asText("");
            if (!text.isBlank()) {
                message = detail.isBlank() ? text : text + " (" + detail + ")";
            }
        } catch (Exception ignored) {
            // The body was not JSON. The HTTP status alone decides.
        }
        return retryable ? SendResult.retryable(code, message) : SendResult.permanent(code, message);
    }
}
