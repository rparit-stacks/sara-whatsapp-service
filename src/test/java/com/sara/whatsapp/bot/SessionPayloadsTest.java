package com.sara.whatsapp.bot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionPayloadsTest {

    @Test
    @SuppressWarnings("unchecked")
    void replyButtonsHaveAtMostThreeShortLabels() {
        Map<String, Object> m = SessionPayloads.replyButtons("Hi, how can we help?", List.of("Track my order", "Get a quote", "Chat in the app"));
        assertEquals("interactive", m.get("type"));
        Map<String, Object> interactive = (Map<String, Object>) m.get("interactive");
        assertEquals("button", interactive.get("type"));
        assertThrows(IllegalArgumentException.class, () -> SessionPayloads.replyButtons("Hi", List.of("a", "b", "c", "d")));
        assertThrows(IllegalArgumentException.class, () -> SessionPayloads.replyButtons("Hi", List.of("This label is far too long")));
        assertThrows(IllegalArgumentException.class, () -> SessionPayloads.replyButtons("Hi", List.of()));
    }

    @Test
    void aLinkButtonNeedsHttps() {
        Map<String, Object> m = SessionPayloads.linkButton("Open it", "Open page", "https://open.studiosara.in/ask");
        assertEquals("interactive", m.get("type"));
        assertThrows(IllegalArgumentException.class, () -> SessionPayloads.linkButton("Open it", "Open page", "http://x.test"));
        assertThrows(IllegalArgumentException.class, () -> SessionPayloads.linkButton("Open it", "Open page", "javascript:alert(1)"));
    }

    @Test
    void bodiesMustBeNonEmptyAndShortEnough() {
        assertThrows(IllegalArgumentException.class, () -> SessionPayloads.text(" "));
        assertThrows(IllegalArgumentException.class, () -> SessionPayloads.text("x".repeat(1025)));
        assertEquals("text", SessionPayloads.text("hello").get("type"));
    }
}
