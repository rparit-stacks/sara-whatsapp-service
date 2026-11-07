package com.sara.whatsapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.domain.EventBinding;
import com.sara.whatsapp.domain.Template;
import com.sara.whatsapp.provider.meta.MetaTemplateClient;
import com.sara.whatsapp.repo.EventBindingRepository;
import com.sara.whatsapp.repo.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateServiceTest {

    private final TemplateRepository repository = mock(TemplateRepository.class);
    private final MetaTemplateClient meta = mock(MetaTemplateClient.class);
    private final EventBindingRepository bindings = mock(EventBindingRepository.class);
    private TemplateService service;
    private Template template;

    @BeforeEach
    void setUp() {
        service = new TemplateService(repository, meta, new ObjectMapper(), bindings);
        template = new Template();
        template.setKey("order_update");
        template.setMetaName("order_update_v1");
        template.setLanguage("en");
        template.setMetaTemplateId("tpl-1");
        when(repository.findByKey("order_update")).thenReturn(Optional.of(template));
        when(repository.save(any(Template.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bindings.findAll()).thenReturn(List.of());
    }

    @Test
    void deleteRemovesTheTemplateInMetaAndHere() {
        service.delete("order_update", true);

        verify(meta).delete("order_update_v1", "tpl-1");
        verify(repository).delete(template);
    }

    @Test
    void deleteCanBeLocalOnly() {
        service.delete("order_update", false);

        verify(meta, never()).delete(anyString(), anyString());
        verify(repository).delete(template);
    }

    @Test
    void aTemplateUsedByABindingIsNotDeleted() {
        EventBinding binding = new EventBinding();
        binding.setEvent("order_shipped");
        binding.setTemplateKey("order_update");
        when(bindings.findAll()).thenReturn(List.of(binding));

        ApiException e = assertThrows(ApiException.class, () -> service.delete("order_update", true));

        assertEquals("IN_USE", e.code());
        verify(meta, never()).delete(anyString(), anyString());
        verify(repository, never()).delete(any(Template.class));
    }

    @Test
    void editSendsComponentsToMetaAndMovesTheTemplateBackToReview() {
        template.setCategory("UTILITY");
        var components = List.<Map<String, Object>>of(Map.of(
                "type", "BODY",
                "text", "Hi {{1}}, your order #{{2}} has been shipped and will reach you soon. Thank you for shopping with us.",
                "example", Map.of("body_text", List.of(List.of("Rohit", "1042")))));

        Template updated = service.updateComponents("order_update", components, null);

        verify(meta).update(eq("tpl-1"), anyMap());
        assertEquals("PENDING", updated.getStatus());
        assertEquals(List.of("1", "2"), updated.getBodyParameters());
    }

    @Test
    void editNeedsAMetaId() {
        template.setMetaTemplateId(null);

        ApiException e = assertThrows(ApiException.class,
                () -> service.updateComponents("order_update", List.of(Map.of("type", "BODY")), null));

        assertEquals("NOT_IN_META", e.code());
    }

    @Test
    void bulkSkipsExistingFailsOneAndCreatesTheRest() {
        var good = List.<Map<String, Object>>of(Map.of("type", "BODY",
                "text", "Hi {{1}}, your order #{{2}} has been shipped and will reach you soon. Thank you for shopping with us.",
                "example", Map.of("body_text", List.of(List.of("Rohit", "1042")))));
        var bad = List.<Map<String, Object>>of(Map.of("type", "BODY", "text", "{{1}} starts with a variable and is too short"));
        when(repository.findByKey("fresh_one")).thenReturn(Optional.empty());
        when(repository.findByKey("bad_one")).thenReturn(Optional.empty());
        when(meta.create(anyMap())).thenReturn(new ObjectMapper().createObjectNode().put("id", "m-1").put("status", "PENDING").put("category", "UTILITY"));

        var results = service.bulkCreate(List.of(
                new TemplateService.BulkItem("order_update", "en", "UTILITY", good),
                new TemplateService.BulkItem("bad_one", "en", "UTILITY", bad),
                new TemplateService.BulkItem("fresh_one", "en", "UTILITY", good)), true);

        assertEquals("skipped", results.get(0).outcome());
        assertEquals("failed", results.get(1).outcome());
        assertEquals("created", results.get(2).outcome());
        verify(meta, org.mockito.Mockito.times(1)).create(anyMap());
    }
}
