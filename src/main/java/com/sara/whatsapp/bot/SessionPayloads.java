package com.sara.whatsapp.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the type-specific part of a free-form WhatsApp message and refuses anything WhatsApp would reject.
 * Only wording owned by the system goes through here; no flow builder can reach it with text of their own.
 */
public final class SessionPayloads {

    public static final int MAX_BODY = 1024;
    public static final int MAX_BUTTON_LABEL = 20;
    public static final int MAX_REPLY_BUTTONS = 3;

    private SessionPayloads() {
    }

    public static Map<String, Object> text(String body) {
        requireBody(body);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "text");
        m.put("text", Map.of("body", body, "preview_url", false));
        return m;
    }

    /** Up to three tappable reply buttons under a message. */
    public static Map<String, Object> replyButtons(String body, List<String> labels) {
        requireBody(body);
        if (labels.isEmpty() || labels.size() > MAX_REPLY_BUTTONS) {
            throw new IllegalArgumentException("A message takes 1 to 3 reply buttons");
        }
        List<Map<String, Object>> buttons = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            requireLabel(label);
            buttons.add(Map.of("type", "reply", "reply", Map.of("id", "btn_" + i, "title", label)));
        }
        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "button");
        interactive.put("body", Map.of("text", body));
        interactive.put("action", Map.of("buttons", buttons));
        return interactive("interactive", interactive);
    }

    /** One button that opens a link. WhatsApp does not allow reply buttons and a link button in the same message. */
    public static Map<String, Object> linkButton(String body, String label, String url) {
        requireBody(body);
        requireLabel(label);
        if (url == null || !url.startsWith("https://")) {
            throw new IllegalArgumentException("A link must start with https://");
        }
        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "cta_url");
        interactive.put("body", Map.of("text", body));
        interactive.put("action", Map.of("name", "cta_url", "parameters", Map.of("display_text", label, "url", url)));
        return interactive("interactive", interactive);
    }

    private static Map<String, Object> interactive(String type, Map<String, Object> interactive) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("interactive", interactive);
        return m;
    }

    private static void requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("A message needs text");
        }
        if (body.length() > MAX_BODY) {
            throw new IllegalArgumentException("A message can be at most " + MAX_BODY + " characters");
        }
    }

    private static void requireLabel(String label) {
        if (label == null || label.isBlank() || label.length() > MAX_BUTTON_LABEL) {
            throw new IllegalArgumentException("A button label is 1 to " + MAX_BUTTON_LABEL + " characters");
        }
    }
}
