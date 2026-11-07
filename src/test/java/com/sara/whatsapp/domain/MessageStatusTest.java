package com.sara.whatsapp.domain;

import org.junit.jupiter.api.Test;

import static com.sara.whatsapp.domain.MessageStatus.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageStatusTest {

    @Test
    void statusMovesForward() {
        assertTrue(QUEUED.canMoveTo(SENDING));
        assertTrue(SENDING.canMoveTo(SENT));
        assertTrue(SENT.canMoveTo(DELIVERED));
        assertTrue(DELIVERED.canMoveTo(READ));
    }

    @Test
    void readCanFollowSentWhenDeliveredWasSkipped() {
        assertTrue(SENT.canMoveTo(READ));
    }

    @Test
    void statusNeverMovesBackwards() {
        assertFalse(READ.canMoveTo(DELIVERED));
        assertFalse(DELIVERED.canMoveTo(SENT));
        assertFalse(SENT.canMoveTo(SENT));
    }

    @Test
    void aDeliveredMessageCannotFail() {
        assertTrue(SENT.canMoveTo(FAILED));
        assertFalse(DELIVERED.canMoveTo(FAILED));
        assertFalse(READ.canMoveTo(FAILED));
    }

    @Test
    void failedAndSkippedAreFinal() {
        assertFalse(FAILED.canMoveTo(SENT));
        assertFalse(SKIPPED.canMoveTo(SENT));
        assertTrue(FAILED.isFinal());
    }

    @Test
    void onlyAQueuedMessageCanBeSkipped() {
        assertTrue(QUEUED.canMoveTo(SKIPPED));
        assertFalse(SENT.canMoveTo(SKIPPED));
    }

    @Test
    void allowedBeforeListsTheValidSourceStates() {
        assertEquals(java.util.Set.of(QUEUED, SENDING, SENT), MessageStatus.allowedBefore(FAILED));
        assertEquals(java.util.Set.of(QUEUED, SENDING, SENT, DELIVERED), MessageStatus.allowedBefore(READ));
    }
}
