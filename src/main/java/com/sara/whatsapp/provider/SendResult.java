package com.sara.whatsapp.provider;

/** Outcome of handing a message to a provider. Success only means the provider accepted it. */
public record SendResult(boolean success, String providerMessageId, boolean retryable, String errorCode, String errorMessage) {

    public static SendResult accepted(String providerMessageId) {
        return new SendResult(true, providerMessageId, false, null, null);
    }

    public static SendResult retryable(String errorCode, String errorMessage) {
        return new SendResult(false, null, true, errorCode, errorMessage);
    }

    public static SendResult permanent(String errorCode, String errorMessage) {
        return new SendResult(false, null, false, errorCode, errorMessage);
    }
}
