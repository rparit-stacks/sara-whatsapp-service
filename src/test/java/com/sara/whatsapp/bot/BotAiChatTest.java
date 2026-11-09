package com.sara.whatsapp.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sara.whatsapp.config.WhatsAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** "Let AI handle from here": the AI answers in free text, remembers the chat, and hands over when it is unsure. */
class BotAiChatTest {

    private static final String FLOW = """
            {"name":"t","nodes":[
             {"id":"start","position":{"x":0,"y":0},"data":{"kind":"start","title":"s"}},
             {"id":"ev_other","data":{"kind":"event","title":"Anything","description":"Anything the customer writes at all.","fallback":true}},
             {"id":"o_ai","data":{"kind":"outcome","title":"AI","outcomeId":"ai_chat","scopes":["products","fabrics"]}}],
             "edges":[{"source":"start","target":"ev_other","sourceHandle":"out"},{"source":"ev_other","target":"o_ai","sourceHandle":"out"}]}
            """;
    private static final String PHONE = "919800000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private final BotFlowService flows = mock(BotFlowService.class);
    private final BotLicenceService licence = mock(BotLicenceService.class);
    private final BotBackendClient backend = mock(BotBackendClient.class);
    private final SessionSender sender = mock(SessionSender.class);
    private final BotConversationService conversations = mock(BotConversationService.class);
    private BotEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        WhatsAppProperties props = new WhatsAppProperties();
        ObjectNode flow = new BotFlowValidator(mapper).sanitize(mapper.readTree(FLOW)).flow();
        when(flows.published()).thenReturn(flow);
        when(licence.isLive()).thenReturn(true);
        when(conversations.reserveAiCall(anyString(), anyInt())).thenReturn(true);
        when(conversations.aiChat(anyString(), any(Instant.class))).thenReturn(Optional.empty());
        when(backend.route(anyString(), anyList())).thenReturn(Optional.empty());
        when(sender.send(anyString(), any(), anyString())).thenReturn(new SessionSender.Result(true, null, "m1"));
        engine = new BotEngine(flows, licence, new BotRouter(backend, conversations, props), backend, sender, conversations, props);
    }

    private List<String> outcomesSent() {
        ArgumentCaptor<String> c = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeast(0)).send(anyString(), any(), c.capture());
        return c.getAllValues();
    }

    @Test
    void reachingTheOutcomeAnswersAndStartsTheChat() {
        when(backend.answer(anyString(), anyList(), anyList())).thenReturn("Yes, we have cotton in many weights.");
        assertEquals(1, engine.handle(PHONE, "Priya", "do you have cotton", false));
        assertEquals(List.of("ai_chat"), outcomesSent());
        verify(conversations).startAiChat(eq(PHONE), eq(List.of("products", "fabrics")), any(Instant.class));
        verify(conversations).recordAiTurn(eq(PHONE), eq("do you have cotton"), eq("Yes, we have cotton in many weights."), any(Instant.class));
    }

    @Test
    void whenTheAiCannotAnswerTheFirstTimeThePersonGoesToTheApp() {
        when(backend.answer(anyString(), anyList(), anyList())).thenReturn("");
        engine.handle(PHONE, "Priya", "something odd", false);
        assertEquals(List.of("ai_chat"), outcomesSent()); // the app link is logged under the outcome that produced it
        verify(conversations, never()).startAiChat(anyString(), anyList(), any(Instant.class));
    }

    @Test
    void whileTheChatRunsTheAiAnswersWithoutRoutingAndKeepsTheHistory() {
        var chat = new BotConversationService.AiChat(List.of("products"), 1, List.of("Q: cotton?", "A: Yes."));
        when(conversations.aiChat(anyString(), any(Instant.class))).thenReturn(Optional.of(chat));
        when(backend.answer("and in silk?", List.of("products"), List.of("Q: cotton?", "A: Yes."))).thenReturn("Silk too, from our silk range.");
        assertEquals(1, engine.handle(PHONE, "Priya", "and in silk?", false));
        verify(backend, never()).route(anyString(), anyList());
        assertEquals(List.of("ai_chat"), outcomesSent());
        verify(conversations).recordAiTurn(eq(PHONE), eq("and in silk?"), eq("Silk too, from our silk range."), any(Instant.class));
    }

    @Test
    void whenTheAiIsNotSureTheTeamIsToldAndTheChatEnds() {
        var chat = new BotConversationService.AiChat(List.of("products"), 2, List.of());
        when(conversations.aiChat(anyString(), any(Instant.class))).thenReturn(Optional.of(chat));
        when(backend.answer(anyString(), anyList(), anyList())).thenReturn("");
        engine.handle(PHONE, "Priya", "I want a refund for order 55", false);
        verify(backend).notifyTeam(PHONE, "I want a refund for order 55");
        verify(conversations).endAiChat(PHONE);
        assertEquals(List.of("handover"), outcomesSent());
    }

    @Test
    void theMainMenuButtonEndsTheChatAndGoesBackToTheFlow() {
        var chat = new BotConversationService.AiChat(List.of("products"), 1, List.of());
        when(conversations.aiChat(anyString(), any(Instant.class))).thenReturn(Optional.of(chat));
        when(backend.answer(anyString(), anyList(), anyList())).thenReturn("Here you go.");
        engine.handle(PHONE, "Priya", "Main menu", false);
        verify(conversations).endAiChat(PHONE);
    }

    @Test
    void theChatEndsAndPointsToTheAppAfterTheReplyLimit() {
        var chat = new BotConversationService.AiChat(List.of("products"), new WhatsAppProperties().getBot().getAiChatMaxReplies(), List.of());
        when(conversations.aiChat(anyString(), any(Instant.class))).thenReturn(Optional.of(chat));
        engine.handle(PHONE, "Priya", "one more thing", false);
        verify(conversations).endAiChat(PHONE);
        verify(backend, never()).answer(anyString(), anyList(), anyList());
        assertEquals(List.of("ai_chat"), outcomesSent()); // the app link is logged under the same outcome id
    }
}
