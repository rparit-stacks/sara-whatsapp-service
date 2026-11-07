package com.sara.whatsapp.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRulesTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> body(String text, String... examples) {
        Map<String, Object> b = map("type", "BODY", "text", text);
        if (examples.length > 0) b.put("example", map("body_text", List.of(List.of(examples))));
        return b;
    }

    private static List<String> check(String category, Map<String, Object>... components) {
        return TemplateRules.validate("order_update", "en", category, List.of(components));
    }

    private static boolean has(List<String> problems, String fragment) {
        return problems.stream().anyMatch(p -> p.toLowerCase().contains(fragment.toLowerCase()));
    }

    @SafeVarargs
    private static List<String> utility(Map<String, Object>... c) { return check("UTILITY", c); }

    private static final String GOOD = "Hello {{1}}, your order #{{2}} has been shipped and will reach you soon. Thank you for shopping with us.";

    @Test
    void acceptsAGoodUtilityTemplate() {
        assertEquals(List.of(), utility(body(GOOD, "Rohit", "1042")));
    }

    @Test
    void rejectsBadNames() {
        assertTrue(has(TemplateRules.validate("Order Update", "en", "UTILITY", List.of(body("Your order is on the way, thanks."))), "lowercase"));
    }

    @Test
    void variableRules() {
        assertTrue(has(utility(body("{{1}} your order is ready for you to collect today at the store.", "A")), "start or end"));
        assertTrue(has(utility(body("Your order is ready for you to collect today at the store, {{1}}", "A")), "start or end"));
        assertTrue(has(utility(body("Hi there, your order {{1}}{{2}} is ready for you to collect today.", "A", "B")), "next to each other"));
        assertEquals(List.of(), utility(body("Hi there, your order {{1}}: {{2}} is ready for you to collect today at the store.", "A", "B")));
        assertTrue(has(utility(body("Hi there, your order {{2}} is ready for you to collect today at the store.", "A")), "numbered"));
        assertTrue(has(utility(body("Hi there, your order {{1}} is ready for you to collect today at the store.")), "example"));
        assertTrue(has(utility(body("Hi {{1}} order {{2}} is {{3}} now.", "a", "b", "c")), "more text"));
    }

    @Test
    void lengthAndFormatting() {
        assertTrue(has(utility(body("x".repeat(1025))), "1024"));
        assertTrue(has(utility(body("Hello there friend.\n\n\nYour order has shipped today.")), "blank line"));
        assertTrue(has(utility(body("Your order has shipped 😀😀😀😀😀😀😀 today, thanks a lot.")), "emoji"));
    }

    @Test
    void headerAndFooterRules() {
        assertTrue(has(utility(map("type", "HEADER", "format", "TEXT", "text", "Hi 😀"), body("Your order has shipped today, thanks a lot.")), "header cannot contain emoji"));
        assertTrue(has(utility(map("type", "HEADER", "format", "TEXT", "text", "x".repeat(61)), body("Your order has shipped today, thanks a lot.")), "60"));
        assertTrue(has(utility(map("type", "HEADER", "format", "IMAGE"), body("Your order has shipped today, thanks a lot.")), "sample image"));
        assertTrue(has(utility(body("Your order has shipped today, thanks a lot."), map("type", "FOOTER", "text", "Hi {{1}}")), "footer cannot have variables"));
    }

    @Test
    void buttonRules() {
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < 3; i++) tooMany.add(map("type", "URL", "text", "Link " + i, "url", "https://studiosara.in/" + i));
        Map<String, Object> buttons = map("type", "BUTTONS", "buttons", tooMany);
        assertTrue(has(utility(body("Your order has shipped today, thanks a lot."), buttons), "at most 2 link"));

        Map<String, Object> http = map("type", "BUTTONS", "buttons", List.of(map("type", "URL", "text", "Track", "url", "http://x.in")));
        assertTrue(has(utility(body("Your order has shipped today, thanks a lot."), http), "https://"));

        Map<String, Object> mixed = map("type", "BUTTONS", "buttons", List.of(
                map("type", "QUICK_REPLY", "text", "Yes"),
                map("type", "URL", "text", "Track", "url", "https://studiosara.in"),
                map("type", "QUICK_REPLY", "text", "No")));
        assertTrue(has(utility(body("Your order has shipped today, thanks a lot."), mixed), "together"));

        Map<String, Object> longLabel = map("type", "BUTTONS", "buttons", List.of(map("type", "QUICK_REPLY", "text", "x".repeat(26))));
        assertTrue(has(utility(body("Your order has shipped today, thanks a lot."), longLabel), "25"));

        Map<String, Object> copy = map("type", "BUTTONS", "buttons", List.of(map("type", "COPY_CODE", "example", "SAVE10")));
        assertTrue(has(utility(body("Your order has shipped today, thanks a lot."), copy), "marketing"));

        Map<String, Object> dynamicNoExample = map("type", "BUTTONS", "buttons", List.of(map("type", "URL", "text", "Track", "url", "https://studiosara.in/t/{{1}}")));
        assertTrue(has(utility(body("Your order has shipped today, thanks a lot."), dynamicNoExample), "example"));

        Map<String, Object> ok = map("type", "BUTTONS", "buttons", List.of(
                map("type", "QUICK_REPLY", "text", "Yes"),
                map("type", "URL", "text", "Track", "url", "https://studiosara.in/t/{{1}}", "example", List.of("1042"))));
        assertEquals(List.of(), utility(body("Your order has shipped today, thanks a lot."), ok));
    }

    @Test
    void categoryGuidelines() {
        assertTrue(has(utility(body("Big sale today with 20% off on everything in the store, thanks.")), "promotion"));
        assertEquals(List.of(), check("MARKETING", body("Big sale today with 20% off on everything in the store, thanks.")));
        assertTrue(has(utility(body("Your OTP for login is ready, please use the code shown above now.")), "login code"));
        assertTrue(has(utility(body("Please share your card number with us so we can finish your order.")), "card"));
        assertEquals(List.of(), utility(body("You can reset your password from the account page whenever you like.")));
    }

    @Test
    void authenticationTemplate() {
        Map<String, Object> ok = map("type", "BUTTONS", "buttons", List.of(map("type", "OTP", "otp_type", "COPY_CODE")));
        assertEquals(List.of(), check("AUTHENTICATION", map("type", "BODY", "add_security_recommendation", true),
                map("type", "FOOTER", "code_expiration_minutes", 10), ok));
        assertTrue(has(check("AUTHENTICATION", map("type", "BODY", "text", "Your code {{1}}"), ok), "fixed by whatsapp"));
        assertTrue(has(check("AUTHENTICATION", map("type", "BODY", "add_security_recommendation", true),
                map("type", "FOOTER", "code_expiration_minutes", 200), ok), "1 and 90"));
        assertTrue(has(check("AUTHENTICATION", map("type", "BODY", "add_security_recommendation", true)), "copy code"));
    }

    @Test
    void emojiCountIgnoresDigitsAndSymbolsInAscii() {
        assertEquals(0, TemplateRules.emojiCount("Order #123 * done 5"));
        assertEquals(2, TemplateRules.emojiCount("📦 ok 💳"));
    }
}
