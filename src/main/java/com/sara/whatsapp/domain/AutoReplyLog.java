package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** When a sender was last sent the auto-reply. The id is the sender's number, so there is one row each. */
@Getter
@Setter
@NoArgsConstructor
@Document("auto_replies")
public class AutoReplyLog {

    @Id
    private String from;
    private Instant lastSentAt;
}
