package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One template sent to many recipients. The audience is either an explicit list of recipients,
 * or the opted-in contacts that carry one of the tags (or all opted-in contacts).
 */
@Getter
@Setter
@NoArgsConstructor
@Document("broadcasts")
@CompoundIndex(name = "ix_status_schedule", def = "{'status': 1, 'scheduledAt': 1}")
public class Broadcast {

    public enum Status { DRAFT, SCHEDULED, RUNNING, PAUSED, COMPLETED, CANCELLED }

    public enum Kind { CAMPAIGN, COLLECTION_LAUNCH }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Recipient {
        private String to;
        /** Values for this recipient, laid over the broadcast's shared variables. */
        private Map<String, String> variables;
    }

    @Id
    private String id;

    @Indexed
    private String clientId;
    private String name;
    private Kind kind = Kind.CAMPAIGN;
    private String templateKey;
    /** Shared by every recipient. */
    private MessageParameters parameters;

    private List<String> tags = List.of();
    private boolean allContacts;
    private List<Recipient> recipients = List.of();

    private Status status = Status.DRAFT;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;

    /** Progress of turning the audience into messages. */
    private String cursor;
    private int recipientCursor;
    private boolean expansionDone;
    private int totalCreated;

    private Instant lockedUntil;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
