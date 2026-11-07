package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A message a customer sent to the business number. Kept for opt-out handling and for clients to read. */
@Getter
@Setter
@NoArgsConstructor
@Document("inbound_messages")
@CompoundIndex(name = "ix_from_received", def = "{'from': 1, 'receivedAt': -1}")
public class InboundMessage {

    @Id
    private String id;

    /** Meta's id of the message. Unique, so a repeated webhook is stored once. */
    @Indexed(unique = true)
    private String providerMessageId;

    /** Sender, E.164 digits without the plus sign. */
    private String from;
    private String name;
    private String type;
    /** Text, or the title of the button or list item the customer chose. Null for media. */
    private String text;

    private boolean optedOut;
    private Instant sentAt;
    private Instant receivedAt;
}
