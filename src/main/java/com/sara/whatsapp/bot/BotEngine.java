package com.sara.whatsapp.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sara.whatsapp.config.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the published workflow when a customer writes: pick the event(s), walk the steps, send the fixed wording through
 * {@link SessionSender}. Does nothing unless the Marketplace licence is live and a flow is published; then the service falls
 * back to its older behaviour (the no-reply notice).
 */
@Service
public class BotEngine {

    private static final Logger log = LoggerFactory.getLogger(BotEngine.class);

    private final BotFlowService flows;
    private final BotLicenceService licence;
    private final BotRouter router;
    private final BotBackendClient backend;
    private final SessionSender sender;
    private final BotConversationService conversations;
    private final WhatsAppProperties.Bot bot;
    private final ZoneId zone;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "bot-engine");
        t.setDaemon(true);
        return t;
    });

    public BotEngine(BotFlowService flows, BotLicenceService licence, BotRouter router, BotBackendClient backend,
                     SessionSender sender, BotConversationService conversations, WhatsAppProperties props) {
        this.flows = flows;
        this.licence = licence;
        this.router = router;
        this.backend = backend;
        this.sender = sender;
        this.conversations = conversations;
        this.bot = props.getBot();
        this.zone = ZoneId.of(props.getUsage().getZone());
    }

    /** True when the bot is the one answering customers right now. */
    public boolean isActive() {
        return licence.isLive() && flows.published() != null;
    }

    /** Hands a message to the bot without holding up the webhook. */
    public void submit(String phone, String profileName, String text, boolean newCustomer) {
        submit(phone, profileName, text, newCustomer, null);
    }

    /** @param inboundMessageId the customer's message id, used to show "typing…" while the bot works. */
    public void submit(String phone, String profileName, String text, boolean newCustomer, String inboundMessageId) {
        pool.submit(() -> {
            try {
                if (inboundMessageId != null) {
                    sender.typing(inboundMessageId);
                }
                handle(phone, profileName, text, newCustomer);
            } catch (Exception e) {
                log.warn("Bot failed on a message from {}: {}", phone, e.toString());
            }
        });
    }

    /** Synchronous core, also used by tests. Returns how many messages were sent. */
    public int handle(String phone, String profileName, String text, boolean newCustomer) {
        ObjectNode flow = flows.published();
        if (!licence.isLive() || flow == null) {
            return 0;
        }
        // "Let AI handle from here": while it is running, the AI answers every message itself, until the customer asks for the menu.
        Optional<BotConversationService.AiChat> chat = conversations.aiChat(phone, Instant.now());
        if (chat != null && chat.isPresent()) {
            if (!leavesAiChat(text)) {
                return aiChatTurn(phone, BotOutcomes.firstName(profileName), text, chat.get());
            }
            conversations.endAiChat(phone);
        }
        Map<String, JsonNode> nodes = new HashMap<>();
        flow.path("nodes").forEach(n -> nodes.put(n.path("id").asText(), n));
        Map<String, List<JsonNode>> bySource = new HashMap<>();
        flow.path("edges").forEach(e -> bySource.computeIfAbsent(e.path("source").asText(), k -> new ArrayList<>()).add(e));

        List<JsonNode> events = bySource.getOrDefault(BotFlowValidator.START_ID, List.of()).stream()
                .map(e -> nodes.get(e.path("target").asText()))
                .filter(n -> n != null && n.path("data").path("kind").asText().equals("event"))
                .collect(Collectors.toList());
        List<BotRouter.Pick> picks = router.route(phone, text, events);
        if (picks.isEmpty()) {
            return 0; // nothing fits and there is no fallback: stay silent, spend nothing
        }
        String name = BotOutcomes.firstName(profileName);
        int[] sent = {0};
        for (BotRouter.Pick pick : picks) {
            if (sent[0] >= bot.getMaxMessagesPerInbound()) {
                break;
            }
            walk(phone, name, text, newCustomer, nodes, bySource, nodes.get(pick.eventId()), sent);
        }
        return sent[0];
    }

    private String next(Map<String, List<JsonNode>> bySource, String id, String handle) {
        return bySource.getOrDefault(id, List.of()).stream()
                .filter(e -> e.path("sourceHandle").asText("out").equals(handle)).findFirst()
                .map(e -> e.path("target").asText()).orElse(null);
    }

    private void walk(String phone, String name, String text, boolean newCustomer, Map<String, JsonNode> nodes,
                      Map<String, List<JsonNode>> bySource, JsonNode event, int[] sent) {
        String id = next(bySource, event.path("id").asText(), "out");
        for (int steps = 0; id != null && steps < 20 && sent[0] < bot.getMaxMessagesPerInbound(); steps++) {
            JsonNode node = nodes.get(id);
            if (node == null) {
                return;
            }
            JsonNode d = node.path("data");
            switch (d.path("kind").asText()) {
                case "end":
                    return;
                case "condition": {
                    boolean yes = switch (d.path("conditionType").asText()) {
                        case "business_hours" -> withinBusinessHours();
                        case "has_orders" -> backend.orderStatus(phone).found();
                        case "new_customer" -> newCustomer;
                        default -> false;
                    };
                    id = next(bySource, id, yes ? "yes" : "no");
                    break;
                }
                case "outcome": {
                    String outcomeId = d.path("outcomeId").asText();
                    String handle = "out";
                    List<String> aiScopes = null;
                    String aiAnswer = null;
                    BotOutcomes.Rendered r;
                    String base = bot.getDeepLinkBase();
                    switch (outcomeId) {
                        case "menu" -> r = BotOutcomes.menu(name);
                        case "order_status" -> {
                            BotBackendClient.OrderStatus o = backend.orderStatus(phone);
                            handle = o.found() ? "found" : "none";
                            r = o.found() ? BotOutcomes.orderFound(name, o.orderNumber(), o.status(), base) : BotOutcomes.orderNone(name, base);
                        }
                        case "open_app" -> r = BotOutcomes.openApp(name, d.path("appPage").asText("/ask"), base);
                        case "answer" -> {
                            List<String> scopes = new ArrayList<>();
                            d.path("scopes").forEach(s -> scopes.add(s.asText()));
                            String a = backend.answer(text, scopes);
                            // No answer (AI off, capped or unsure): send them to the app, which is the safe thing to do.
                            r = a.isBlank() ? BotOutcomes.openApp(name, "/ask", base) : BotOutcomes.answer(name, a);
                        }
                        case "ai_chat" -> {
                            List<String> scopes = new ArrayList<>();
                            d.path("scopes").forEach(x -> scopes.add(x.asText()));
                            String a = conversations.reserveAiCall(phone, bot.getDailyAiCap()) ? backend.chat(text, scopes, List.of()) : "";
                            if (a.isBlank()) {
                                r = BotOutcomes.openApp(name, "/ask", base); // the AI could not help: the app can, and it is free
                            } else {
                                r = BotOutcomes.chat(a);
                                aiScopes = scopes;
                                aiAnswer = a;
                            }
                        }
                        case "ask_details" -> {
                            String need = d.path("need").asText(BotCatalog.NEEDS.get(0));
                            String c = backend.compose("ask_details", name, need, text);
                            r = c.isBlank() ? BotOutcomes.askDetails(name, need) : BotOutcomes.written(c);
                        }
                        case "confirm" -> {
                            String c = backend.compose("confirm", name, "", text);
                            r = c.isBlank() ? BotOutcomes.confirm(name) : BotOutcomes.written(c);
                        }
                        case "feedback" -> r = BotOutcomes.feedback(name);
                        case "handover" -> {
                            backend.notifyTeam(phone, text);
                            r = BotOutcomes.handover(name, base);
                        }
                        case "closed" -> r = BotOutcomes.closed(name, base);
                        default -> {
                            return;
                        }
                    }
                    SessionSender.Result result = sender.send(phone, r.payload(), outcomeId);
                    if (!result.sent()) {
                        log.info("Bot message to {} not sent: {}", phone, result.reason());
                        return; // a closed window, an opt-out, a cap or a failure: do not carry on
                    }
                    sent[0]++;
                    if (aiScopes != null) {
                        Instant until = Instant.now().plus(java.time.Duration.ofMinutes(Math.max(1, bot.getAiChatMinutes())));
                        conversations.startAiChat(phone, aiScopes, until);
                        conversations.recordAiTurn(phone, text, aiAnswer, until);
                    }
                    id = next(bySource, id, handle);
                    break;
                }
                default:
                    id = next(bySource, id, "out");
            }
        }
    }

    private static DayOfWeek day(String s) {
        return switch (s.trim().toUpperCase().substring(0, 3)) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    /** A button tap or "menu" ends the AI chat and goes back to the normal flow. */
    private static boolean leavesAiChat(String text) {
        String t = text == null ? "" : text.trim();
        return t.equalsIgnoreCase("menu") || t.equalsIgnoreCase("stop") || BotCatalog.TAPS.stream().anyMatch(t::equalsIgnoreCase);
    }

    /** One message inside an AI chat. When the AI is unsure, or limits are reached, the chat moves to the app or to the team. */
    private int aiChatTurn(String phone, String name, String text, BotConversationService.AiChat chat) {
        String base = bot.getDeepLinkBase();
        if (chat.replies() >= bot.getAiChatMaxReplies() || !conversations.reserveAiCall(phone, bot.getDailyAiCap())) {
            conversations.endAiChat(phone);
            return sender.send(phone, BotOutcomes.openApp(name, "/ask", base).payload(), "ai_chat").sent() ? 1 : 0;
        }
        String a = backend.chat(text, chat.scopes(), chat.turns());
        if (a.isBlank()) {
            backend.notifyTeam(phone, text);
            conversations.endAiChat(phone);
            return sender.send(phone, BotOutcomes.handover(name, base).payload(), "handover").sent() ? 1 : 0;
        }
        if (!sender.send(phone, BotOutcomes.chat(a).payload(), "ai_chat").sent()) {
            return 0;
        }
        conversations.recordAiTurn(phone, text, a, Instant.now().plus(java.time.Duration.ofMinutes(Math.max(1, bot.getAiChatMinutes()))));
        return 1;
    }

    boolean withinBusinessHours() {
        ZonedDateTime now = Instant.now().atZone(zone);
        Set<DayOfWeek> days = Stream.of(bot.getBusinessDays().split(",")).filter(x -> x.trim().length() >= 3)
                .map(BotEngine::day).collect(Collectors.toSet());
        LocalTime t = now.toLocalTime();
        return days.contains(now.getDayOfWeek()) && !t.isBefore(LocalTime.parse(bot.getBusinessStart())) && t.isBefore(LocalTime.parse(bot.getBusinessEnd()));
    }
}
