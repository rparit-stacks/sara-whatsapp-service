package com.sara.whatsapp.service;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.api.dto.SendMessageRequest;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.EventBinding;
import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.MessageStatus;
import com.sara.whatsapp.domain.Priority;
import com.sara.whatsapp.domain.Template;
import com.sara.whatsapp.repo.EventBindingRepository;
import com.sara.whatsapp.repo.MessageRepository;
import com.sara.whatsapp.repo.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageServiceTest {

    private final MessageRepository messages = mock(MessageRepository.class);
    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final EventBindingRepository bindings = mock(EventBindingRepository.class);
    private final StatusService status = mock(StatusService.class);
    private final ContactService contacts = mock(ContactService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);

    private MessageService service;
    private ApiClient client;

    @BeforeEach
    void setUp() {
        service = new MessageService(messages, templates, bindings, status, contacts, mongo, new WhatsAppProperties());
        client = new ApiClient();
        client.setId("client-1");
        when(contacts.consentOf(anyString())).thenReturn(com.sara.whatsapp.domain.ConsentStatus.UNKNOWN);
        when(messages.findByClientIdAndClientMessageId(anyString(), anyString())).thenReturn(Optional.empty());
        when(messages.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            if (m.getId() == null) m.setId("msg-1");
            return m;
        });
    }

    private Template template(String key, String status, List<String> bodyParameters) {
        Template t = new Template();
        t.setKey(key);
        t.setMetaName(key + "_v1");
        t.setLanguage("en");
        t.setCategory("UTILITY");
        t.setStatus(status);
        t.setBodyParameters(bodyParameters);
        when(templates.findByKey(key)).thenReturn(Optional.of(t));
        return t;
    }

    private static SendMessageRequest request(String clientMessageId, String templateKey, String event, String to, Map<String, String> vars) {
        MessageParameters params = new MessageParameters();
        params.setVariables(vars);
        return new SendMessageRequest(clientMessageId, templateKey, event, to, params, null, null);
    }

    @Test
    void validMessageIsQueuedAsQueued() {
        template("order_update", "APPROVED", List.of("1"));

        var result = service.queue(client, request("c-1", "order_update", null, "9876543210", Map.of("1", "A-9")));

        assertFalse(result.duplicate());
        Message m = result.message();
        assertEquals(MessageStatus.QUEUED, m.getStatus());
        assertEquals("919876543210", m.getTo());
        assertEquals("UTILITY", m.getCategory());
        assertNotNull(m.getNextAttemptAt());
        verify(status, never()).skip(anyString(), anyString());
    }

    @Test
    void sameClientMessageIdReturnsTheOriginal() {
        Message original = new Message();
        original.setId("orig");
        when(messages.findByClientIdAndClientMessageId("client-1", "c-1")).thenReturn(Optional.of(original));

        var result = service.queue(client, request("c-1", "order_update", null, "9876543210", Map.of()));

        assertTrue(result.duplicate());
        assertEquals("orig", result.message().getId());
        verify(messages, never()).save(any());
    }

    @Test
    void raceOnTheUniqueIndexReturnsTheExistingMessage() {
        template("order_update", "APPROVED", List.of());
        Message original = new Message();
        original.setId("orig");
        when(messages.save(any(Message.class))).thenThrow(new DuplicateKeyException("dup"));
        when(messages.findByClientIdAndClientMessageId("client-1", "c-2"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(original));

        var result = service.queue(client, request("c-2", "order_update", null, "9876543210", Map.of()));

        assertTrue(result.duplicate());
        assertEquals("orig", result.message().getId());
    }

    @Test
    void invalidNumberIsSkippedAndNeverClaimable() {
        template("order_update", "APPROVED", List.of());

        var result = service.queue(client, request("c-3", "order_update", null, "123", Map.of()));

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).save(saved.capture());
        assertNull(saved.getValue().getNextAttemptAt());
        verify(status).skip("msg-1", "INVALID_NUMBER");
        assertNotNull(result.message());
    }

    @Test
    void pausedAndPendingTemplatesAreSkippedWithAReason() {
        template("paused_one", "PAUSED", List.of());
        template("pending_one", "PENDING", List.of());

        service.queue(client, request("c-4", "paused_one", null, "9876543210", Map.of()));
        verify(status).skip("msg-1", "TEMPLATE_PAUSED");

        service.queue(client, request("c-5", "pending_one", null, "9876543210", Map.of()));
        verify(status).skip("msg-1", "TEMPLATE_NOT_APPROVED");
    }

    @Test
    void unknownTemplateStatusIsAllowed() {
        template("manual_one", null, List.of());

        service.queue(client, request("c-6", "manual_one", null, "9876543210", Map.of()));

        verify(status, never()).skip(anyString(), anyString());
    }

    @Test
    void unknownTemplateIsRejected() {
        when(templates.findByKey("nope")).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class,
                () -> service.queue(client, request("c-7", "nope", null, "9876543210", Map.of())));
        assertEquals("TEMPLATE_MISSING", e.code());
    }

    @Test
    void missingBodyVariableIsRejected() {
        template("order_update", "APPROVED", List.of("1", "2"));

        ApiException e = assertThrows(ApiException.class,
                () -> service.queue(client, request("c-8", "order_update", null, "9876543210", Map.of("1", "only one"))));
        assertEquals("MISSING_PARAMETER", e.code());
    }

    @Test
    void mediaHeaderTemplateNeedsAHeader() {
        Template t = template("invoice", "APPROVED", List.of());
        t.setHeaderType("DOCUMENT");

        ApiException e = assertThrows(ApiException.class,
                () -> service.queue(client, request("c-9", "invoice", null, "9876543210", Map.of())));
        assertEquals("MISSING_HEADER", e.code());
    }

    @Test
    void eventBindingSuppliesTemplateAndDefaults() {
        template("order_update", "APPROVED", List.of());
        EventBinding binding = new EventBinding();
        binding.setEvent("order_shipped");
        binding.setTemplateKey("order_update");
        binding.setDefaultPriority(Priority.HIGH);
        binding.setDefaultTtlSeconds(300);
        when(bindings.findByEvent("order_shipped")).thenReturn(Optional.of(binding));

        var result = service.queue(client, request("c-10", null, "order_shipped", "9876543210", Map.of()));

        Message m = result.message();
        assertEquals("order_update", m.getTemplateKey());
        assertEquals(Priority.HIGH, m.getPriority());
        assertEquals(0, m.getPriorityRank());
        assertNotNull(m.getExpiresAt());
        assertEquals("order_shipped", m.getEvent());
    }

    @Test
    void unboundEventAndMissingNamesAreRejected() {
        when(bindings.findByEvent("ghost")).thenReturn(Optional.empty());

        assertEquals("EVENT_NOT_BOUND", assertThrows(ApiException.class,
                () -> service.queue(client, request("c-11", null, "ghost", "9876543210", Map.of()))).code());
        assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class,
                () -> service.queue(client, request("c-12", null, null, "9876543210", Map.of()))).code());
    }

    @Test
    void retryOnlyAppliesToFailedMessagesOfTheSameClient() {
        Message failed = new Message();
        failed.setId("m1");
        failed.setClientId("client-1");
        failed.setStatus(MessageStatus.FAILED);
        when(messages.findById("m1")).thenReturn(Optional.of(failed));

        service.retry(client, "m1");
        verify(mongo).updateFirst(any(), any(), eq(Message.class));

        Message queued = new Message();
        queued.setId("m2");
        queued.setClientId("client-1");
        queued.setStatus(MessageStatus.QUEUED);
        when(messages.findById("m2")).thenReturn(Optional.of(queued));
        assertEquals("NOT_RETRYABLE", assertThrows(ApiException.class, () -> service.retry(client, "m2")).code());

        Message other = new Message();
        other.setId("m3");
        other.setClientId("someone-else");
        other.setStatus(MessageStatus.FAILED);
        when(messages.findById("m3")).thenReturn(Optional.of(other));
        assertThrows(ApiException.class, () -> service.retry(client, "m3"));
    }

    @Test
    void marketingTemplateNeedsAnOptIn() {
        Template t = template("offer", "APPROVED", List.of());
        t.setCategory("MARKETING");

        service.queue(client, request("c-20", "offer", null, "9876543210", Map.of()));

        verify(status).skip("msg-1", "NO_CONSENT");
    }

    @Test
    void marketingTemplateGoesToAnOptedInContact() {
        Template t = template("offer", "APPROVED", List.of());
        t.setCategory("MARKETING");
        when(contacts.consentOf("919876543210")).thenReturn(com.sara.whatsapp.domain.ConsentStatus.OPTED_IN);

        service.queue(client, request("c-21", "offer", null, "9876543210", Map.of()));

        verify(status, never()).skip(anyString(), anyString());
    }

    @Test
    void optedOutContactGetsNothingExceptAuthentication() {
        when(contacts.consentOf("919876543210")).thenReturn(com.sara.whatsapp.domain.ConsentStatus.OPTED_OUT);
        template("order_update", "APPROVED", List.of());
        Template auth = template("login_code", "APPROVED", List.of());
        auth.setCategory("AUTHENTICATION");

        service.queue(client, request("c-22", "order_update", null, "9876543210", Map.of()));
        verify(status).skip("msg-1", "NO_CONSENT");

        org.mockito.Mockito.clearInvocations(status);
        service.queue(client, request("c-23", "login_code", null, "9876543210", Map.of()));
        verify(status, never()).skip(anyString(), anyString());
    }
}
