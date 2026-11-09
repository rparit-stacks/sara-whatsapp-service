package com.sara.whatsapp.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sara.whatsapp.config.WhatsAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The decisions the engine makes: which event, which steps, and when it stays quiet. */
class BotEngineTest {

    private static final String FLOW = """
            {"name":"t","nodes":[
             {"id":"start","position":{"x":0,"y":0},"data":{"kind":"start","title":"s"}},
             {"id":"ev_order","data":{"kind":"event","title":"Track","description":"The customer asks about an order.","examples":["where is my order","order status"],"tapButton":"Track my order"}},
             {"id":"ev_quote","data":{"kind":"event","title":"Quote","description":"The customer wants a quote.","examples":["quote","bulk order"]}},
             {"id":"ev_hours","data":{"kind":"event","title":"Person","description":"The customer wants a person.","examples":["talk to someone"]}},
             {"id":"ev_info","data":{"kind":"event","title":"Info","description":"The customer asks about products.","examples":["do you have cotton"]}},
             {"id":"ev_other","data":{"kind":"event","title":"Other","description":"Anything else at all.","fallback":true}},
             {"id":"o_order","data":{"kind":"outcome","title":"Status","outcomeId":"order_status"}},
             {"id":"o_none","data":{"kind":"outcome","title":"None","outcomeId":"open_app","appPage":"/ask"}},
             {"id":"o_quote","data":{"kind":"outcome","title":"Quote","outcomeId":"open_app","appPage":"/inquiry"}},
             {"id":"c_hours","data":{"kind":"condition","title":"Open","conditionType":"business_hours"}},
             {"id":"o_hand","data":{"kind":"outcome","title":"Hand","outcomeId":"handover"}},
             {"id":"o_closed","data":{"kind":"outcome","title":"Closed","outcomeId":"closed"}},
             {"id":"o_ans","data":{"kind":"outcome","title":"Answer","outcomeId":"answer","scopes":["products"]}},
             {"id":"o_fb","data":{"kind":"outcome","title":"Fallback","outcomeId":"open_app","appPage":"/ask"}}],
             "edges":[
              {"source":"start","target":"ev_order","sourceHandle":"out"},{"source":"start","target":"ev_quote","sourceHandle":"out"},
              {"source":"start","target":"ev_hours","sourceHandle":"out"},{"source":"start","target":"ev_info","sourceHandle":"out"},{"source":"start","target":"ev_other","sourceHandle":"out"},
              {"source":"ev_order","target":"o_order","sourceHandle":"out"},{"source":"o_order","target":"o_none","sourceHandle":"none"},
              {"source":"ev_quote","target":"o_quote","sourceHandle":"out"},
              {"source":"ev_hours","target":"c_hours","sourceHandle":"out"},{"source":"c_hours","target":"o_hand","sourceHandle":"yes"},{"source":"c_hours","target":"o_closed","sourceHandle":"no"},
              {"source":"ev_info","target":"o_ans","sourceHandle":"out"},
              {"source":"ev_other","target":"o_fb","sourceHandle":"out"}]}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BotFlowService flows = mock(BotFlowService.class);
    private final BotLicenceService licence = mock(BotLicenceService.class);
    private final BotBackendClient backend = mock(BotBackendClient.class);
    private final SessionSender sender = mock(SessionSender.class);
    private final BotConversationService conversations = mock(BotConversationService.class);
    private WhatsAppProperties props;
    private BotEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        props = new WhatsAppProperties();
        ObjectNode flow = new BotFlowValidator(mapper).sanitize(mapper.readTree(FLOW)).flow();
        when(flows.published()).thenReturn(flow);
        when(licence.isLive()).thenReturn(true);
        when(conversations.reserveAiCall(anyString(), anyInt())).thenReturn(true);
        when(backend.route(anyString(), anyList())).thenReturn(Optional.empty());
        when(backend.orderStatus(anyString())).thenReturn(new BotBackendClient.OrderStatus(false, null, null, null));
        when(backend.answer(anyString(), anyList())).thenReturn("");
        when(sender.send(anyString(), any(), anyString())).thenReturn(new SessionSender.Result(true, null, "m1"));
        engine = new BotEngine(flows, licence, new BotRouter(backend, conversations, props), backend, sender, conversations, props);
    }

    private List<String> outcomesSent() {
        ArgumentCaptor<String> c = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeast(0)).send(anyString(), any(), c.capture());
        return c.getAllValues();
    }

    @Test
    void doesNothingWhileTheLicenceIsNotLive() {
        when(licence.isLive()).thenReturn(false);
        assertEquals(0, engine.handle("919800000000", "Priya", "hello", false));
        verifyNoInteractions(sender);
    }

    @Test
    void doesNothingWhileNoFlowIsPublished() {
        when(flows.published()).thenReturn(null);
        assertEquals(0, engine.handle("919800000000", "Priya", "hello", false));
        verifyNoInteractions(sender);
    }

    @Test
    void aButtonTapIsRoutedWithoutAskingTheAi() {
        when(backend.orderStatus(anyString())).thenReturn(new BotBackendClient.OrderStatus(true, "1042", "shipped", "2026-11-01"));
        assertEquals(1, engine.handle("919800000000", "Priya Sharma", "Track my order", false));
        verify(backend, never()).route(anyString(), anyList());
        assertEquals(List.of("order_status"), outcomesSent());
    }

    @Test
    void anOrderThatIsNotFoundTakesTheNoOrderBranchToTheApp() {
        assertEquals(2, engine.handle("919800000000", "Priya", "Track my order", false));
        assertEquals(List.of("order_status", "open_app"), outcomesSent());
    }

    @Test
    void theAiChoiceIsUsedWhenItIsConfidentAndInTheFlow() {
        when(backend.route(anyString(), anyList())).thenReturn(Optional.of(List.of(new BotBackendClient.Choice("ev_quote", 0.9))));
        assertEquals(1, engine.handle("919800000000", "Priya", "mujhe 500 kurte chahiye price batao", false));
        assertEquals(List.of("open_app"), outcomesSent());
    }

    @Test
    void anAiChoiceOutsideTheFlowOrBelowConfidenceFallsBackToTheFallbackEvent() {
        when(backend.route(anyString(), anyList())).thenReturn(Optional.of(List.of(
                new BotBackendClient.Choice("ev_not_in_flow", 0.99), new BotBackendClient.Choice("ev_quote", 0.2))));
        engine.handle("919800000000", "Priya", "blah blah", false);
        assertEquals(List.of("open_app"), outcomesSent()); // the fallback outcome
    }

    @Test
    void whenTheAiIsDownThePhrasesAreMatchedInstead() {
        when(backend.route(anyString(), anyList())).thenReturn(Optional.empty());
        engine.handle("919800000000", "Priya", "I need a bulk order please", false);
        verify(sender).send(eq("919800000000"), any(), eq("open_app"));
    }

    @Test
    void theAiIsNotCalledOnceTheCustomerDailyCapIsUsed() {
        when(conversations.reserveAiCall(anyString(), anyInt())).thenReturn(false);
        engine.handle("919800000000", "Priya", "something about quotes", false);
        verify(backend, never()).route(anyString(), anyList());
        verify(sender, atLeastOnce()).send(anyString(), any(), anyString());
    }

    @Test
    void anAnswerThatTheAiCannotGiveSendsThemToTheAppInstead() {
        engine.handle("919800000000", "Priya", "do you have cotton", false);
        assertEquals(List.of("answer"), outcomesSent()); // sent as the "answer" outcome, with the app page as its content
        verify(backend).answer(eq("do you have cotton"), eq(List.of("products")));
    }

    @Test
    void aClosedWindowOrACapStopsTheChain() {
        when(sender.send(anyString(), any(), anyString())).thenReturn(new SessionSender.Result(false, "WINDOW_CLOSED", null));
        assertEquals(0, engine.handle("919800000000", "Priya", "Track my order", false));
        verify(sender, times(1)).send(anyString(), any(), anyString()); // it tried once and stopped
    }

    @Test
    void theBusinessHoursConditionPicksYesOrNo() {
        props.getBot().setBusinessDays("MON,TUE,WED,THU,FRI,SAT,SUN");
        props.getBot().setBusinessStart("00:00");
        props.getBot().setBusinessEnd("23:59");
        engine = new BotEngine(flows, licence, new BotRouter(backend, conversations, props), backend, sender, conversations, props);
        engine.handle("919800000000", "Priya", "talk to someone", false);
        verify(sender).send(anyString(), any(), eq("handover"));
        verify(backend).notifyTeam(anyString(), anyString());

        clearInvocations(sender);
        props.getBot().setBusinessStart("00:00");
        props.getBot().setBusinessEnd("00:00"); // never open
        engine.handle("919800000000", "Priya", "talk to someone", false);
        verify(sender).send(anyString(), any(), eq("closed"));
    }

    @Test
    void theBotNeverSendsMoreThanTheCapForOneIncomingMessage() {
        props.getBot().setMaxMessagesPerInbound(1);
        assertEquals(1, engine.handle("919800000000", "Priya", "Track my order", false)); // would have sent 2
    }
}
