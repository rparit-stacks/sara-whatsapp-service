package com.sara.whatsapp.api.dto;

import com.sara.whatsapp.domain.Message;

import java.time.Instant;

/** What the API returns for a message. */
public record MessageView(
        String id,
        String clientMessageId,
        String event,
        String templateKey,
        String to,
        String status,
        String skipReason,
        String provider,
        String providerMessageId,
        String priority,
        int attempts,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant sentAt,
        Instant deliveredAt,
        Instant readAt,
        Instant expiresAt) {

    public static MessageView of(Message m) {
        return new MessageView(m.getId(), m.getClientMessageId(), m.getEvent(), m.getTemplateKey(), m.getTo(),
                m.getStatus().name(), m.getSkipReason(), m.getProvider(), m.getProviderMessageId(),
                m.getPriority().name(), m.getAttempts(), m.getErrorCode(), m.getErrorMessage(), m.getCreatedAt(),
                m.getSentAt(), m.getDeliveredAt(), m.getReadAt(), m.getExpiresAt());
    }
}
