package com.sara.whatsapp.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a message. The forward order is QUEUED, SENDING, SENT, DELIVERED, READ.
 * FAILED and SKIPPED are side exits, see {@link #canMoveTo}.
 */
public enum MessageStatus {
    QUEUED(0),
    SENDING(1),
    SENT(2),
    DELIVERED(3),
    READ(4),
    FAILED(-1),
    SKIPPED(-1);

    private final int rank;

    MessageStatus(int rank) {
        this.rank = rank;
    }

    public boolean isFinal() {
        return this == FAILED || this == SKIPPED || this == READ;
    }

    /**
     * Status only moves forward. A provider "delivered" arriving after "read" is ignored, and a
     * "failed" is accepted only until the message is delivered (a delivered message cannot fail).
     */
    public boolean canMoveTo(MessageStatus next) {
        if (this == next) {
            return false;
        }
        return switch (next) {
            case FAILED -> this == QUEUED || this == SENDING || this == SENT;
            case SKIPPED -> this == QUEUED;
            default -> rank >= 0 && next.rank > rank;
        };
    }

    /** Statuses from which {@code next} is a valid move, for use in a conditional database update. */
    public static Set<MessageStatus> allowedBefore(MessageStatus next) {
        Set<MessageStatus> out = EnumSet.noneOf(MessageStatus.class);
        for (MessageStatus s : values()) {
            if (s.canMoveTo(next)) {
                out.add(s);
            }
        }
        return out;
    }
}
