package com.sara.whatsapp.api;

import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.EventBinding;
import com.sara.whatsapp.domain.Priority;
import com.sara.whatsapp.repo.EventBindingRepository;
import com.sara.whatsapp.repo.TemplateRepository;
import com.sara.whatsapp.security.ApiAuth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** Maps a caller's own event names to templates. Optional: a caller can always send a templateKey. */
@RestController
@RequestMapping("/v1/event-bindings")
public class EventBindingController {

    public record BindingRequest(String templateKey, Priority defaultPriority, Integer defaultTtlSeconds, Boolean active) {
    }

    private static final Pattern EVENT_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");

    private final EventBindingRepository bindings;
    private final TemplateRepository templates;

    public EventBindingController(EventBindingRepository bindings, TemplateRepository templates) {
        this.bindings = bindings;
        this.templates = templates;
    }

    @GetMapping
    public List<EventBinding> list(HttpServletRequest http) {
        ApiAuth.require(http, ApiClient.SCOPE_READ);
        return bindings.findAll();
    }

    @PutMapping("/{event}")
    public EventBinding put(HttpServletRequest http, @PathVariable String event, @RequestBody BindingRequest request) {
        ApiAuth.require(http, ApiClient.SCOPE_TEMPLATES);
        if (!EVENT_NAME.matcher(event).matches()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "event may use letters, digits, '_', '-' and '.' (max 100)");
        }
        if (request.templateKey() == null || templates.findByKey(request.templateKey()).isEmpty()) {
            throw ApiException.unprocessable("TEMPLATE_MISSING", "templateKey must be an existing template");
        }
        EventBinding binding = bindings.findByEvent(event).orElseGet(EventBinding::new);
        binding.setEvent(event);
        binding.setTemplateKey(request.templateKey());
        binding.setDefaultPriority(request.defaultPriority() != null ? request.defaultPriority() : Priority.NORMAL);
        binding.setDefaultTtlSeconds(request.defaultTtlSeconds());
        binding.setActive(request.active() == null || request.active());
        binding.setUpdatedBy(ApiAuth.client(http).getName());
        binding.setUpdatedAt(Instant.now());
        return bindings.save(binding);
    }
}
