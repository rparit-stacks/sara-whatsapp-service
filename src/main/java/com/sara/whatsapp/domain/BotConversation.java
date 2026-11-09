package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** What the bot knows about one customer's chat: when they last wrote (the 24-hour window), and today's counters. */
@Getter
@Setter
@NoArgsConstructor
@Document("bot_conversations")
public class BotConversation {

    /** The customer's number, E.164 digits without the plus sign. */
    @Id
    private String phone;
    private Instant firstSeenAt;
    private Instant lastInboundAt;
    /** The local day the counters below belong to, for example 2026-11-09. */
    private String day;
    private int botMessages;
    private int aiCalls;
    /** While in the future, a person has the chat and the bot stays quiet. */
    private Instant humanUntil;
    /** "Let AI handle from here": while aiUntil is in the future the AI answers this customer's messages, on these topics. */
    private java.util.List<String> aiScopes;
    private Instant aiUntil;
    private int aiReplies;
    /** The last few lines of the AI chat (Q: / A: pairs), so a follow-up question makes sense. */
    private java.util.List<String> aiTurns;
}
