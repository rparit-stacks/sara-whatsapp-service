package com.sara.whatsapp.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server-side rules for a flow. {@link #sanitize} rebuilds a flow from allowed fields only (so a hand-edited or imported
 * flow cannot smuggle in free text, template names or AI instructions); {@link #validate} reports what would stop it working.
 * Mirrors sara-frontend-ecom/src/components/admin/whatsapp-bot/{sanitize,validate}.ts.
 */
public final class BotFlowValidator {

    public static final String START_ID = "start";
    private static final Set<String> FORBIDDEN_KEYS = Set.of("text", "instructions", "template", "templateName", "prompt", "message");

    public record Issue(String level, String nodeId, String text) {
    }

    public record Sanitized(ObjectNode flow, List<String> dropped) {
    }

    private final ObjectMapper mapper;

    public BotFlowValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private static String str(JsonNode v, int max) {
        if (v == null || !v.isTextual()) {
            return "";
        }
        String s = v.asText().replace("<", "").replace(">", "").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** The connection points a node offers. */
    static List<String> outputs(String kind, ObjectNode data) {
        return switch (kind) {
            case "start", "event" -> List.of("out");
            case "condition" -> List.of("yes", "no");
            case "outcome" -> BotCatalog.outcome(data.path("outcomeId").asText("")).map(o -> o.outputs().stream().map(BotCatalog.Output::id).toList()).orElse(List.of());
            default -> List.of();
        };
    }

    public Sanitized sanitize(JsonNode input) {
        List<String> dropped = new ArrayList<>();
        if (input == null || !input.path("nodes").isArray() || !input.path("edges").isArray()) {
            return new Sanitized(null, List.of("This is not a flow."));
        }
        ObjectNode flow = mapper.createObjectNode();
        String name = str(input.get("name"), 60);
        flow.put("name", name.isEmpty() ? "Studio Sara bot" : name);
        ArrayNode nodes = flow.putArray("nodes");
        Map<String, ObjectNode> byId = new HashMap<>();
        int events = 0;
        boolean hasStart = false;

        for (JsonNode raw : input.get("nodes")) {
            JsonNode rd = raw.path("data");
            String kind = rd.path("kind").asText("");
            if (!BotCatalog.KINDS.contains(kind)) {
                dropped.add("A step of an unknown type was removed.");
                continue;
            }
            if (kind.equals("start")) {
                if (hasStart) {
                    continue;
                }
                hasStart = true;
            }
            if (kind.equals("event") && ++events > BotCatalog.MAX_EVENTS) {
                dropped.add("Extra events beyond the limit were removed.");
                continue;
            }
            String id = kind.equals("start") ? START_ID : str(raw.get("id"), 40);
            if (id.isEmpty() || byId.containsKey(id) || (id.equals(START_ID) && !kind.equals("start"))) {
                id = "n_" + UUID.randomUUID().toString().substring(0, 6);
            }
            ObjectNode data = mapper.createObjectNode();
            data.put("kind", kind);
            data.put("title", str(rd.get("title"), 40));

            if (kind.equals("event")) {
                data.put("description", str(rd.get("description"), 200));
                ArrayNode examples = data.putArray("examples");
                if (rd.path("examples").isArray()) {
                    for (JsonNode e : rd.get("examples")) {
                        String ex = str(e, 80);
                        if (!ex.isEmpty() && examples.size() < 6) {
                            examples.add(ex);
                        }
                    }
                }
                String tap = rd.path("tapButton").asText("");
                if (BotCatalog.TAPS.contains(tap)) {
                    data.put("tapButton", tap);
                }
                if (rd.path("fallback").asBoolean(false)) {
                    data.put("fallback", true);
                }
            }
            if (kind.equals("outcome")) {
                var spec = BotCatalog.outcome(rd.path("outcomeId").asText(""));
                if (spec.isEmpty()) {
                    dropped.add("A step that did something outside the allowed list was removed.");
                    continue;
                }
                data.put("outcomeId", spec.get().id());
                if (spec.get().params().contains("appPage") && BotCatalog.APP_PAGES.containsKey(rd.path("appPage").asText(""))) {
                    data.put("appPage", rd.get("appPage").asText());
                }
                if (spec.get().params().contains("scopes")) {
                    ArrayNode scopes = data.putArray("scopes");
                    Set<String> seen = new LinkedHashSet<>();
                    if (rd.path("scopes").isArray()) {
                        rd.get("scopes").forEach(s -> {
                            if (BotCatalog.ANSWER_SCOPES.containsKey(s.asText())) {
                                seen.add(s.asText());
                            }
                        });
                    }
                    seen.forEach(scopes::add);
                }
                if (spec.get().params().contains("need") && BotCatalog.NEEDS.contains(rd.path("need").asText(""))) {
                    data.put("need", rd.get("need").asText());
                }
            }
            if (kind.equals("condition")) {
                String c = rd.path("conditionType").asText("");
                data.put("conditionType", BotCatalog.CONDITIONS.contains(c) ? c : "business_hours");
            }
            rd.fieldNames().forEachRemaining(k -> {
                if (FORBIDDEN_KEYS.contains(k)) {
                    dropped.add("Free text, template names and AI instructions are not allowed and were removed.");
                }
            });

            ObjectNode node = mapper.createObjectNode();
            node.put("id", id);
            node.put("type", "bot");
            ObjectNode pos = node.putObject("position");
            pos.put("x", raw.path("position").path("x").asDouble(0));
            pos.put("y", raw.path("position").path("y").asDouble(0));
            node.set("data", data);
            nodes.add(node);
            byId.put(id, node);
        }
        if (!hasStart) {
            ObjectNode start = mapper.createObjectNode();
            start.put("id", START_ID);
            start.put("type", "bot");
            start.putObject("position").put("x", 0).put("y", 220);
            start.putObject("data").put("kind", "start").put("title", "Customer sends a message");
            ((ArrayNode) flow.get("nodes")).insert(0, start);
            byId.put(START_ID, start);
        }

        ArrayNode edges = flow.putArray("edges");
        Set<String> usedHandles = new HashSet<>();
        Set<String> usedPairs = new HashSet<>();
        for (JsonNode e : input.get("edges")) {
            ObjectNode s = byId.get(e.path("source").asText(""));
            ObjectNode t = byId.get(e.path("target").asText(""));
            if (s == null || t == null || t.path("id").asText().equals(START_ID)) {
                continue;
            }
            String sKind = s.path("data").path("kind").asText();
            String tKind = t.path("data").path("kind").asText();
            String handle = e.path("sourceHandle").isTextual() ? e.get("sourceHandle").asText() : "out";
            if (!outputs(sKind, (ObjectNode) s.get("data")).contains(handle)) {
                continue;
            }
            boolean fromStart = s.path("id").asText().equals(START_ID);
            if (tKind.equals("event") != fromStart) {
                continue; // events are reached only from the start, and the start reaches only events
            }
            String sid = s.path("id").asText();
            String tid = t.path("id").asText();
            if (!fromStart && !usedHandles.add(sid + "|" + handle)) {
                continue; // one line per connection point
            }
            if (!usedPairs.add(sid + "|" + handle + "|" + tid)) {
                continue;
            }
            ObjectNode edge = mapper.createObjectNode();
            edge.put("id", "e_" + sid + "_" + handle + "_" + tid);
            edge.put("source", sid);
            edge.put("target", tid);
            edge.put("sourceHandle", handle);
            edge.put("type", "smoothstep");
            edges.add(edge);
        }
        return new Sanitized(flow, new ArrayList<>(new LinkedHashSet<>(dropped)));
    }

    /** What would stop the bot working (errors) and what is worth a look (warnings). Expects a sanitized flow. */
    public List<Issue> validate(ObjectNode flow) {
        List<Issue> issues = new ArrayList<>();
        Map<String, JsonNode> nodes = new HashMap<>();
        flow.path("nodes").forEach(n -> nodes.put(n.path("id").asText(), n));
        Map<String, List<JsonNode>> bySource = new HashMap<>();
        flow.path("edges").forEach(e -> bySource.computeIfAbsent(e.path("source").asText(), k -> new ArrayList<>()).add(e));

        List<JsonNode> events = nodes.values().stream().filter(n -> kind(n).equals("event")).toList();
        if (!nodes.containsKey(START_ID)) {
            issues.add(new Issue("error", null, "The starting point is missing."));
        }
        if (events.isEmpty()) {
            issues.add(new Issue("error", null, "Add at least one event, so the bot knows what customers may ask for."));
        }
        Set<String> fromStart = new HashSet<>();
        bySource.getOrDefault(START_ID, List.of()).forEach(e -> fromStart.add(e.path("target").asText()));

        for (JsonNode n : events) {
            String id = n.path("id").asText();
            JsonNode d = n.path("data");
            String label = "Event \"" + d.path("title").asText("(untitled)") + "\"";
            if (!fromStart.contains(id)) {
                issues.add(new Issue("error", id, label + " is not connected to the starting point."));
            }
            if (!connected(bySource, id, "out")) {
                issues.add(new Issue("error", id, label + " has nothing after it."));
            }
            if (d.path("description").asText("").trim().length() < 10) {
                issues.add(new Issue("error", id, label + " needs a description of at least a sentence."));
            }
        }
        if (events.stream().noneMatch(n -> n.path("data").path("fallback").asBoolean(false))) {
            issues.add(new Issue("warning", null, "There is no fallback event. A message that fits nothing gets no reply."));
        }

        for (JsonNode n : nodes.values()) {
            String id = n.path("id").asText();
            JsonNode d = n.path("data");
            if (kind(n).equals("outcome")) {
                var spec = BotCatalog.outcome(d.path("outcomeId").asText(""));
                String label = "Outcome \"" + d.path("title").asText("(untitled)") + "\"";
                if (spec.isEmpty()) {
                    issues.add(new Issue("error", id, label + " has no outcome chosen."));
                    continue;
                }
                if (spec.get().params().contains("appPage") && !BotCatalog.APP_PAGES.containsKey(d.path("appPage").asText(""))) {
                    issues.add(new Issue("error", id, label + " needs an app page."));
                }
                if (spec.get().params().contains("scopes") && d.path("scopes").size() == 0) {
                    issues.add(new Issue("error", id, label + " needs at least one topic the AI may answer about."));
                }
                if (spec.get().params().contains("need") && !BotCatalog.NEEDS.contains(d.path("need").asText(""))) {
                    issues.add(new Issue("error", id, label + " needs to say what we are asking for."));
                }
            }
        }

        Set<String> reached = new HashSet<>();
        List<String> stack = new ArrayList<>(List.of(START_ID));
        while (!stack.isEmpty()) {
            String id = stack.remove(stack.size() - 1);
            if (!reached.add(id)) {
                continue;
            }
            bySource.getOrDefault(id, List.of()).forEach(e -> stack.add(e.path("target").asText()));
        }
        nodes.keySet().stream().filter(id -> !reached.contains(id)).forEach(id ->
                issues.add(new Issue("warning", id, "A step can never be reached.")));

        if (hasCycle(START_ID, bySource, new HashMap<>())) {
            issues.add(new Issue("error", null, "The flow loops back on itself."));
        }
        return issues;
    }

    public static boolean hasErrors(List<Issue> issues) {
        return issues.stream().anyMatch(i -> i.level().equals("error"));
    }

    private static String kind(JsonNode n) {
        return n.path("data").path("kind").asText("");
    }

    private static boolean connected(Map<String, List<JsonNode>> bySource, String id, String handle) {
        return bySource.getOrDefault(id, List.of()).stream().anyMatch(e -> e.path("sourceHandle").asText("out").equals(handle));
    }

    private static boolean hasCycle(String id, Map<String, List<JsonNode>> bySource, Map<String, Integer> mark) {
        mark.put(id, 1);
        for (JsonNode e : bySource.getOrDefault(id, List.of())) {
            String t = e.path("target").asText();
            int m = mark.getOrDefault(t, 0);
            if (m == 1 || (m == 0 && hasCycle(t, bySource, mark))) {
                return true;
            }
        }
        mark.put(id, 2);
        return false;
    }
}
