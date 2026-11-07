package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Optional mapping from a name chosen by the client to a template, with defaults. */
@Getter
@Setter
@NoArgsConstructor
@Document("event_bindings")
public class EventBinding {

    @Id
    private String id;

    @Indexed(unique = true)
    private String event;
    private String templateKey;
    private Priority defaultPriority = Priority.NORMAL;
    /** Seconds a message may wait before it is skipped as expired. Null means no expiry. */
    private Integer defaultTtlSeconds;
    private boolean active = true;
    private String updatedBy;
    private Instant updatedAt;
}
