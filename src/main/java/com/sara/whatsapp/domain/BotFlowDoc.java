package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** The bot's one workflow: the draft being edited and the published copy the bot runs. Both are stored as JSON text. */
@Getter
@Setter
@NoArgsConstructor
@Document("bot_flows")
public class BotFlowDoc {

    public static final String ID = "main";

    @Id
    private String id = ID;
    private String draft;
    private String published;
    private int publishedVersion;
    private Instant publishedAt;
    private String publishedBy;
    private Instant updatedAt;
    private String updatedBy;
}
