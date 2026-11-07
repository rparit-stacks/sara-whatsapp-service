package com.sara.whatsapp.service;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.Broadcast;
import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.Template;
import com.sara.whatsapp.repo.BroadcastRepository;
import com.sara.whatsapp.repo.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BroadcastServiceTest {

    private final BroadcastRepository broadcasts = mock(BroadcastRepository.class);
    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final MessageService messageService = mock(MessageService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);

    private BroadcastService service;
    private ApiClient client;

    @BeforeEach
    void setUp() {
        service = new BroadcastService(broadcasts, templates, messageService, mock(OutboundEventService.class),
                mongo, new WhatsAppProperties());
        client = new ApiClient();
        client.setId("client-1");
        client.setName("studio-sara");
        Template t = new Template();
        t.setKey("sale");
        t.setCategory("MARKETING");
        when(templates.findByKey("sale")).thenReturn(Optional.of(t));
        when(broadcasts.save(any(Broadcast.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Broadcast.Recipient recipient(String to) {
        Broadcast.Recipient r = new Broadcast.Recipient();
        r.setTo(to);
        return r;
    }

    private static BroadcastService.CreateRequest request(List<String> tags, Boolean all, List<Broadcast.Recipient> recipients, Instant at) {
        return new BroadcastService.CreateRequest("Diwali sale", Broadcast.Kind.CAMPAIGN, "sale", null, tags, all, recipients, at);
    }

    @Test
    void audienceByTagsCreatesADraft() {
        Broadcast b = service.create(client, request(List.of("vip"), null, null, null));

        assertEquals(Broadcast.Status.DRAFT, b.getStatus());
        assertEquals(List.of("vip"), b.getTags());
        assertEquals("studio-sara", b.getCreatedBy());
    }

    @Test
    void aFutureScheduleCreatesAScheduledBroadcast() {
        Broadcast b = service.create(client, request(null, true, null, Instant.now().plusSeconds(3600)));

        assertEquals(Broadcast.Status.SCHEDULED, b.getStatus());
        assertTrue(b.isAllContacts());
    }

    @Test
    void exactlyOneKindOfAudienceIsRequired() {
        assertThrows(ApiException.class, () -> service.create(client, request(null, null, null, null)));
        assertThrows(ApiException.class,
                () -> service.create(client, request(List.of("vip"), null, List.of(recipient("9876543210")), null)));
    }

    @Test
    void scheduleMustBeInTheFutureAndTemplateMustExist() {
        assertThrows(ApiException.class,
                () -> service.create(client, request(null, true, null, Instant.now().minusSeconds(60))));
        var unknown = new BroadcastService.CreateRequest("x", null, "nope", null, List.of("a"), null, null, null);
        assertEquals("TEMPLATE_MISSING", assertThrows(ApiException.class, () -> service.create(client, unknown)).code());
    }

    @Test
    void recipientListNeedsANumberForEveryone() {
        assertThrows(ApiException.class, () -> service.create(client, request(null, null, List.of(recipient(" ")), null)));
    }

    @Test
    void recipientVariablesAreLaidOverSharedOnes() {
        MessageParameters shared = new MessageParameters();
        shared.setVariables(Map.of("1", "shared", "2", "keep"));

        MessageParameters merged = BroadcastService.merged(shared, Map.of("1", "own"));

        assertEquals("own", merged.getVariables().get("1"));
        assertEquals("keep", merged.getVariables().get("2"));
        assertEquals("shared", shared.getVariables().get("1"));
    }

    @Test
    void onlyADraftOrScheduledBroadcastCanBeEdited() {
        Broadcast running = new Broadcast();
        running.setId("b1");
        running.setClientId("client-1");
        running.setStatus(Broadcast.Status.RUNNING);
        when(broadcasts.findById("b1")).thenReturn(Optional.of(running));

        ApiException e = assertThrows(ApiException.class,
                () -> service.update(client, "b1", request(List.of("vip"), null, null, null)));
        assertEquals("NOT_EDITABLE", e.code());

        Broadcast draft = new Broadcast();
        draft.setId("b2");
        draft.setClientId("client-1");
        draft.setStatus(Broadcast.Status.DRAFT);
        when(broadcasts.findById("b2")).thenReturn(Optional.of(draft));
        Broadcast updated = service.update(client, "b2", request(List.of("loyal"), null, null, null));
        assertEquals(List.of("loyal"), updated.getTags());
    }

    @Test
    void pauseOnlyWorksOnARunningBroadcast() {
        Broadcast b = new Broadcast();
        b.setId("b3");
        b.setClientId("client-1");
        when(broadcasts.findById("b3")).thenReturn(Optional.of(b));
        when(mongo.updateFirst(any(), any(), eq(Broadcast.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertEquals("NOT_PAUSABLE", assertThrows(ApiException.class, () -> service.pause(client, "b3")).code());

        when(mongo.updateFirst(any(), any(), eq(Broadcast.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        service.pause(client, "b3");
    }
}
