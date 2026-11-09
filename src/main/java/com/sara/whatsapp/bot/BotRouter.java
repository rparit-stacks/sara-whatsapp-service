package com.sara.whatsapp.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.sara.whatsapp.config.WhatsAppProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which event(s) a message fits. A tap on a button is matched exactly with no AI. Free text goes to the AI (within the
 * customer's daily cap), and whatever it returns is cut down to the events the flow offers. If the AI is unavailable, capped
 * or unsure, plain phrase matching is used, and then the fallback event.
 */
@Component
public class BotRouter {

    public record Pick(String eventId, String how) {
    }

    private static final Set<String> STOP = Set.of("the", "a", "an", "is", "to", "my", "me", "i", "of", "in", "on", "and", "or", "it", "for", "you", "we", "do", "have");

    private final BotBackendClient backend;
    private final BotConversationService conversations;
    private final WhatsAppProperties.Bot bot;

    public BotRouter(BotBackendClient backend, BotConversationService conversations, WhatsAppProperties props) {
        this.backend = backend;
        this.conversations = conversations;
        this.bot = props.getBot();
    }

    /** @param events the event nodes that are connected to the start, in flow order */
    public List<Pick> route(String phone, String text, List<JsonNode> events) {
        String clean = text == null ? "" : text.trim();
        List<Pick> picks = new ArrayList<>();

        // 1. A button tap: exact, free.
        for (JsonNode e : events) {
            String tap = e.path("data").path("tapButton").asText("");
            if (!tap.isEmpty() && tap.equalsIgnoreCase(clean) && picks.size() < BotCatalog.MAX_EVENTS_PER_MESSAGE) {
                picks.add(new Pick(e.path("id").asText(), "tap"));
            }
        }
        if (!picks.isEmpty()) {
            return picks;
        }
        if (!clean.isEmpty()) {
            // 2. The AI, if the customer still has AI choices today.
            if (conversations.reserveAiCall(phone, bot.getDailyAiCap())) {
                Optional<List<BotBackendClient.Choice>> ai = backend.route(clean, describe(events));
                if (ai.isPresent()) {
                    Set<String> allowed = new HashSet<>();
                    events.forEach(e -> allowed.add(e.path("id").asText()));
                    for (BotBackendClient.Choice c : ai.get()) {
                        if (allowed.contains(c.id()) && c.confidence() >= bot.getMinConfidence() && picks.size() < BotCatalog.MAX_EVENTS_PER_MESSAGE
                                && !isFallback(events, c.id())) {
                            picks.add(new Pick(c.id(), "ai"));
                        }
                    }
                    return picks.isEmpty() ? fallback(events) : picks;
                }
            }
            // 3. The AI could not be used: match the example phrases.
            for (Map.Entry<String, Double> e : lexical(clean, events).entrySet()) {
                if (picks.size() < BotCatalog.MAX_EVENTS_PER_MESSAGE) {
                    picks.add(new Pick(e.getKey(), "phrases"));
                }
            }
        }
        return picks.isEmpty() ? fallback(events) : picks;
    }

    private static boolean isFallback(List<JsonNode> events, String id) {
        return events.stream().anyMatch(e -> e.path("id").asText().equals(id) && e.path("data").path("fallback").asBoolean(false));
    }

    private static List<Pick> fallback(List<JsonNode> events) {
        return events.stream().filter(e -> e.path("data").path("fallback").asBoolean(false)).findFirst()
                .map(e -> List.of(new Pick(e.path("id").asText(), "fallback"))).orElse(List.of());
    }

    /** What the AI is shown: every non-fallback event with its description and example phrases. */
    private static List<Map<String, Object>> describe(List<JsonNode> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode e : events) {
            JsonNode d = e.path("data");
            if (d.path("fallback").asBoolean(false)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.path("id").asText());
            m.put("title", d.path("title").asText(""));
            m.put("description", d.path("description").asText(""));
            List<String> examples = new ArrayList<>();
            d.path("examples").forEach(x -> examples.add(x.asText()));
            m.put("examples", examples);
            out.add(m);
        }
        return out;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9\\u0900-\\u097f]+")) {
            if (!w.isEmpty() && !STOP.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** Plain matching of the message against each event's example phrases and title. Best matches first, 0.6 or better. */
    static Map<String, Double> lexical(String text, List<JsonNode> events) {
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> words = new HashSet<>(tokens(text));
        Map<String, Double> scores = new LinkedHashMap<>();
        for (JsonNode e : events) {
            JsonNode d = e.path("data");
            if (d.path("fallback").asBoolean(false)) {
                continue;
            }
            List<String> phrases = new ArrayList<>();
            d.path("examples").forEach(x -> phrases.add(x.asText()));
            phrases.add(d.path("title").asText(""));
            double best = 0;
            for (String ph : phrases) {
                String p = ph.trim().toLowerCase(Locale.ROOT);
                if (p.isEmpty()) {
                    continue;
                }
                if (lower.contains(p)) {
                    best = 1;
                    continue;
                }
                List<String> pt = tokens(p);
                if (pt.isEmpty()) {
                    continue;
                }
                best = Math.max(best, pt.stream().filter(words::contains).count() / (double) pt.size());
            }
            if (best >= 0.6) {
                scores.put(e.path("id").asText(), best);
            }
        }
        Map<String, Double> sorted = new LinkedHashMap<>();
        scores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())).forEach(en -> sorted.put(en.getKey(), en.getValue()));
        return sorted;
    }
}
