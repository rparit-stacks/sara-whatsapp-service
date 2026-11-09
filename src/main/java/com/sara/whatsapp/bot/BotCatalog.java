package com.sara.whatsapp.bot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a flow is allowed to say and do. This is the server's copy of the list the builder shows. A flow is rebuilt
 * from these values only, so it can never carry free text to send, a template name or an instruction for the AI.
 * Keep it in step with sara-frontend-ecom/src/components/admin/whatsapp-bot/catalog.ts.
 */
public final class BotCatalog {

    public static final int MAX_EVENTS = 12;
    public static final int MAX_EVENTS_PER_MESSAGE = 2;

    public record Output(String id, String label) {
    }

    /** One thing the bot can do. {@code template} is private: which approved wording carries it outside the 24-hour window. */
    public record Outcome(String id, String label, List<String> params, List<Output> outputs, String template) {
    }

    private static Output out() {
        return new Output("out", "Next");
    }

    public static final List<Outcome> OUTCOMES = List.of(
            new Outcome("menu", "Show the main menu", List.of(), List.of(), "ai_main_menu"),
            new Outcome("order_status", "Share the order status", List.of(), List.of(new Output("found", "Order found"), new Output("none", "No order")), "ai_order_status"),
            new Outcome("open_app", "Send them to the app", List.of("appPage"), List.of(out()), "ai_info_link"),
            new Outcome("answer", "Answer from our knowledge", List.of("scopes"), List.of(out()), "ai_info_text"),
            new Outcome("ai_chat", "Let AI handle from here", List.of("scopes"), List.of(), "ai_info_text"),
            new Outcome("ask_details", "Ask for more details", List.of("need"), List.of(out()), "ai_design_needs_info"),
            new Outcome("confirm", "Confirm we got their request", List.of(), List.of(out()), "ai_ticket_created"),
            new Outcome("feedback", "Ask how it went", List.of(), List.of(new Output("loved", "Loved it"), new Output("okay", "It was okay"), new Output("unhappy", "Not happy")), "ai_feedback_request"),
            new Outcome("handover", "Hand over to the team", List.of(), List.of(out()), "ai_info_link"),
            new Outcome("closed", "Tell them we are closed", List.of(), List.of(out()), "ai_info_link"));

    public static Optional<Outcome> outcome(String id) {
        return OUTCOMES.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    /** Where a button can send a customer, with the fixed sentence that goes with it. */
    public static final Map<String, String> APP_PAGES = new LinkedHashMap<>();
    static {
        APP_PAGES.put("/ask", "Our assistant and team are in the app, free of charge. Tap below to chat.");
        APP_PAGES.put("/inquiry", "Quotes are quickest in the app. Tap below to start one.");
        APP_PAGES.put("/orders", "You can see every detail of your orders in the app.");
        APP_PAGES.put("/products", "You can browse our full collection in the app.");
        APP_PAGES.put("/cart", "Your cart is waiting for you in the app.");
        APP_PAGES.put("/portal", "You can follow your custom projects and quotes in the app.");
        APP_PAGES.put("/contact", "You can reach our team from the app.");
    }

    public static final Map<String, String> ANSWER_SCOPES = Map.of(
            "products", "Products and prints",
            "fabrics", "Fabrics",
            "pricing", "Pricing and minimums",
            "custom", "Custom design and embroidery",
            "orders", "Orders and delivery",
            "policies", "Shipping, returns and policies");

    public static final List<String> NEEDS = List.of(
            "the fabric you would like to use",
            "the quantity you need",
            "your design file or idea",
            "your order number",
            "the date you need it by");

    /** Buttons a customer can tap in the bot's messages. */
    public static final List<String> TAPS = List.of("Track my order", "Get a quote", "Chat in the app", "Main menu", "Loved it", "It was okay", "Not happy");

    public static final Set<String> CONDITIONS = Set.of("business_hours", "has_orders", "new_customer");
    public static final Set<String> KINDS = Set.of("start", "event", "outcome", "condition", "end");

    private BotCatalog() {
    }
}
