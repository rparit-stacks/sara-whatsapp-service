package com.sara.whatsapp.domain;

/** Sending lane. Lower rank is claimed first. Broadcast messages always use BULK. */
public enum Priority {
    HIGH(0),
    NORMAL(1),
    BULK(2);

    private final int rank;

    Priority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
