package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One WhatsApp message. The collection is both the message log and the send queue. */
@Getter
@Setter
@NoArgsConstructor
@Document("messages")
@CompoundIndexes({
        @CompoundIndex(name = "uq_client_message", def = "{'clientId': 1, 'clientMessageId': 1}", unique = true),
        @CompoundIndex(name = "ix_queue", def = "{'status': 1, 'nextAttemptAt': 1, 'priorityRank': 1, 'createdAt': 1}"),
        @CompoundIndex(name = "ix_recipient", def = "{'to': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "ix_client_created", def = "{'clientId': 1, 'createdAt': -1}")
})
public class Message {

    @Id
    private String id;

    private String clientId;
    private String clientMessageId;

    /** Optional name the caller uses for its own event (resolved through an event binding). */
    private String event;
    private String templateKey;
    /** Copied from the template when the message is created, used for spend estimates. */
    private String category;

    /** E.164 digits without the plus sign. */
    private String to;
    private String recipientCountry;

    private MessageParameters parameters;

    private Priority priority = Priority.NORMAL;
    private int priorityRank = Priority.NORMAL.rank();

    private MessageStatus status = MessageStatus.QUEUED;
    private String skipReason;

    private String provider;
    @Indexed(sparse = true)
    private String providerMessageId;

    private int attempts;
    private Instant nextAttemptAt;
    private Instant lockedUntil;
    private String lockedBy;
    private Instant expiresAt;

    private String errorCode;
    private String errorMessage;

    @Indexed(sparse = true)
    private String broadcastId;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant sentAt;
    private Instant deliveredAt;
    private Instant readAt;

    public void setPriority(Priority priority) {
        this.priority = priority;
        this.priorityRank = priority.rank();
    }
}
