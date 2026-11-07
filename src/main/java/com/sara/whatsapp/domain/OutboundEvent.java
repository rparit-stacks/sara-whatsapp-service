package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/** An event waiting to be delivered to a client's callback URL. */
@Getter
@Setter
@NoArgsConstructor
@Document("outbound_events")
@CompoundIndex(name = "ix_due", def = "{'status': 1, 'nextAttemptAt': 1}")
public class OutboundEvent {

    public enum Status { PENDING, SENDING, DELIVERED, FAILED }

    @Id
    private String id;

    private String clientId;
    private String type;
    private Map<String, Object> data;

    private Status status = Status.PENDING;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant lockedUntil;
    private String lastError;
    private Instant createdAt;
    private Instant deliveredAt;
}
