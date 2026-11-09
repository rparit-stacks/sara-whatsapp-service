package com.sara.whatsapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.ConsentStatus;
import com.sara.whatsapp.domain.InboundMessage;
import com.sara.whatsapp.repo.InboundMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboundServiceTest {

    private final InboundMessageRepository repository = mock(InboundMessageRepository.class);
    private final ContactService contacts = mock(ContactService.class);
    private final ClientService clients = mock(ClientService.class);
    private final OutboundEventService outbound = mock(OutboundEventService.class);
    private final AutoReplyService autoReply = mock(AutoReplyService.class);
    private final org.springframework.data.mongodb.core.MongoTemplate mongo = mock(org.springframework.data.mongodb.core.MongoTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private InboundService service;

    @BeforeEach
    void setUp() {
        service = new InboundService(repository, contacts, clients, outbound, autoReply, mongo, new WhatsAppProperties());
        ApiClient client = new ApiClient();
        client.setId("client-1");
        when(clients.list()).thenReturn(List.of(client));
    }

    private com.fasterxml.jackson.databind.JsonNode value(String text) throws Exception {
        return mapper.readTree("{\"contacts\":[{\"wa_id\":\"919876543210\",\"profile\":{\"name\":\"Asha\"}}],"
                + "\"messages\":[{\"id\":\"wamid.1\",\"from\":\"919876543210\",\"timestamp\":\"1700000000\","
                + "\"type\":\"text\",\"text\":{\"body\":\"" + text + "\"}}]}");
    }

    @Test
    void optOutKeywordIsMatchedIgnoringCaseAndPunctuation() {
        assertTrue(service.isOptOut("STOP"));
        assertTrue(service.isOptOut(" stop. "));
        assertTrue(service.isOptOut("Stop all"));
        assertFalse(service.isOptOut("please do not stop my order"));
        assertFalse(service.isOptOut(null));
    }

    @Test
    void stopReplyOptsTheSenderOutAndNotifiesClients() throws Exception {
        service.handle(value("STOP"));

        ArgumentCaptor<InboundMessage> saved = ArgumentCaptor.forClass(InboundMessage.class);
        verify(repository).save(saved.capture());
        assertTrue(saved.getValue().isOptedOut());
        assertEquals("Asha", saved.getValue().getName());
        verify(contacts).setConsent("919876543210", ConsentStatus.OPTED_OUT, "whatsapp-reply", "Asha");
        verify(outbound).publish(eq("client-1"), eq("message.received"), any());
    }

    @Test
    void ordinaryReplyIsStoredWithoutChangingConsent() throws Exception {
        service.handle(value("When will it arrive?"));

        verify(repository).save(any(InboundMessage.class));
        verify(contacts, never()).setConsent(anyString(), any(), anyString(), any());
        verify(outbound).publish(eq("client-1"), eq("message.received"), any());
    }

    @Test
    void aRepeatedWebhookIsIgnored() throws Exception {
        when(repository.save(any(InboundMessage.class))).thenThrow(new DuplicateKeyException("dup"));

        service.handle(value("STOP"));

        verify(contacts, never()).setConsent(anyString(), any(), anyString(), any());
        verify(outbound, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void anOrdinaryMessageTriggersTheAutoReplyButAStopDoesNot() throws Exception {
        service.handle(value("hello"));
        verify(autoReply).replyTo("919876543210");

        org.mockito.Mockito.clearInvocations(autoReply);
        service.handle(value("STOP"));
        verify(autoReply, never()).replyTo(anyString());
    }
}
