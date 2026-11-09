package com.sara.whatsapp.bot;

import java.util.List;
import java.util.Map;

/**
 * The fixed wording of every outcome, owned by the system. A flow can choose an outcome and its allowed options, but never the
 * words. Mirrors the test chat in the builder (components/admin/whatsapp-bot/engine.ts).
 */
public final class BotOutcomes {

    /** A message ready to send: its type-specific payload and a short label for the log. */
    public record Rendered(Map<String, Object> payload) {
    }

    private BotOutcomes() {
    }

    public static String firstName(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return "there";
        }
        String first = profileName.trim().split("\\s+")[0];
        return first.length() > 30 ? "there" : first;
    }

    private static String link(String base, String path) {
        return base.replaceAll("/+$", "") + (path != null && BotCatalog.APP_PAGES.containsKey(path) ? path : "/ask");
    }

    /** A plain-text reply the AI wrote (already checked by the backend). The fixed wording is used when there is none. */
    public static Rendered written(String text) {
        return new Rendered(SessionPayloads.text(text));
    }

    public static Rendered menu(String name) {
        return new Rendered(SessionPayloads.replyButtons("Hi " + name + ", welcome to Studio Sara. How can we help you today? Tap an option below to continue.",
                List.of("Track my order", "Get a quote", "Chat in the app")));
    }

    public static Rendered orderFound(String name, String orderNumber, String status, String base) {
        String order = orderNumber == null || orderNumber.isBlank() ? "" : " " + orderNumber;
        return new Rendered(SessionPayloads.linkButton("Hi " + name + ", your Studio Sara order" + order + " is now " + (status == null ? "placed" : status)
                + ". Details: you can see every detail in the app. Tap below to see your order.", "View order", link(base, "/orders")));
    }

    public static Rendered orderNone(String name, String base) {
        return new Rendered(SessionPayloads.linkButton("Hi " + name + ", here is what you asked for: we could not find an order for this phone number. Tap below for more details.",
                "Open page", link(base, "/ask")));
    }

    public static Rendered openApp(String name, String page, String base) {
        String line = BotCatalog.APP_PAGES.getOrDefault(page, BotCatalog.APP_PAGES.get("/ask"));
        return new Rendered(SessionPayloads.linkButton("Hi " + name + ", here is what you asked for: " + line + " Tap below for more details.", "Open page", link(base, page)));
    }

    public static Rendered answer(String name, String answer) {
        return new Rendered(SessionPayloads.replyButtons("Hi " + name + ", here is the information you asked for: " + answer + " Reply here if you need anything else.", List.of("Main menu")));
    }

    public static Rendered askDetails(String name, String need) {
        return new Rendered(SessionPayloads.text("Hi " + name + ", we need a little more information to move ahead: " + need + " Please reply to this message with the details."));
    }

    public static Rendered confirm(String name) {
        return new Rendered(SessionPayloads.text("Hi " + name + ", we have noted your request. Our team will get back to you as soon as possible."));
    }

    public static Rendered feedback(String name) {
        return new Rendered(SessionPayloads.replyButtons("Hi " + name + ", thank you for shopping with Studio Sara. How was your experience? We would love to hear from you.",
                List.of("Loved it", "It was okay", "Not happy")));
    }

    public static Rendered handover(String name, String base) {
        return new Rendered(SessionPayloads.linkButton("Hi " + name + ", here is what you asked for: our assistant and team are in the app, free of charge. Tap below to chat.",
                "Open page", link(base, "/ask")));
    }

    public static Rendered closed(String name, String base) {
        return new Rendered(SessionPayloads.linkButton("Hi " + name + ", here is what you asked for: we are closed right now, and our assistant in the app can help any time. Tap below for more details.",
                "Open page", link(base, "/ask")));
    }
}
