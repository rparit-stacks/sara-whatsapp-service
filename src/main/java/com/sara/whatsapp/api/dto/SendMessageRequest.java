package com.sara.whatsapp.api.dto;

import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Send one template message. Name the template with {@code templateKey}, or with the caller's
 * own {@code event} name when an event binding exists.
 */
public record SendMessageRequest(
        @NotBlank @Size(max = 128) String clientMessageId,
        String templateKey,
        String event,
        @NotBlank String to,
        MessageParameters parameters,
        Priority priority,
        Instant expiresAt) {
}
