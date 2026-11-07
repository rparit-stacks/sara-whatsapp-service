package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One status change or provider receipt for a message. */
@Getter
@Setter
@NoArgsConstructor
@Document("message_events")
public class MessageEvent {

    @Id
    private String id;

    @Indexed
    private String messageId;
    private String status;
    private Instant at;
    private String errorCode;
    private String detail;
}
