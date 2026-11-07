package com.sara.whatsapp.api;

import com.sara.whatsapp.api.dto.MessageView;
import com.sara.whatsapp.api.dto.PageView;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.Broadcast;
import com.sara.whatsapp.security.ApiAuth;
import com.sara.whatsapp.service.BroadcastService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/broadcasts")
public class BroadcastController {

    public record BroadcastView(String id, String name, String kind, String templateKey, String status,
                                Instant scheduledAt, Instant startedAt, Instant completedAt,
                                int recipientsListed, List<String> tags, boolean allContacts,
                                int messagesCreated, Map<String, Long> counts, Instant createdAt) {

        static BroadcastView of(Broadcast b, Map<String, Long> counts) {
            return new BroadcastView(b.getId(), b.getName(), b.getKind().name(), b.getTemplateKey(), b.getStatus().name(),
                    b.getScheduledAt(), b.getStartedAt(), b.getCompletedAt(), b.getRecipients().size(), b.getTags(),
                    b.isAllContacts(), b.getTotalCreated(), counts, b.getCreatedAt());
        }
    }

    private final BroadcastService service;

    public BroadcastController(BroadcastService service) {
        this.service = service;
    }

    /** Creates a DRAFT, or a SCHEDULED broadcast when scheduledAt is given. Nothing is sent until it starts. */
    @PostMapping
    public ResponseEntity<BroadcastView> create(HttpServletRequest http, @RequestBody BroadcastService.CreateRequest request) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_BROADCAST);
        Broadcast b = service.create(client, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(BroadcastView.of(b, Map.of()));
    }

    @GetMapping
    public PageView<BroadcastView> list(HttpServletRequest http,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_BROADCAST);
        var result = service.list(client, page, size);
        List<BroadcastView> content = result.getContent().stream().map(b -> BroadcastView.of(b, service.counts(b.getId()))).toList();
        return new PageView<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{id}")
    public BroadcastView get(HttpServletRequest http, @PathVariable String id) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_BROADCAST);
        Broadcast b = service.get(client, id);
        return BroadcastView.of(b, service.counts(id));
    }

    /** Edits a broadcast that has not started (DRAFT or SCHEDULED). */
    @PutMapping("/{id}")
    public BroadcastView update(HttpServletRequest http, @PathVariable String id, @RequestBody BroadcastService.CreateRequest request) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_BROADCAST);
        return BroadcastView.of(service.update(client, id, request), service.counts(id));
    }

    @PostMapping("/{id}/pause")
    public BroadcastView pause(HttpServletRequest http, @PathVariable String id) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_BROADCAST);
        return BroadcastView.of(service.pause(client, id), service.counts(id));
    }

    @PostMapping("/{id}/resume")
    public BroadcastView resume(HttpServletRequest http, @PathVariable String id) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_BROADCAST);
        return BroadcastView.of(service.resume(client, id), service.counts(id));
    }

    @PostMapping("/{id}/start")
    public BroadcastView start(HttpServletRequest http, @PathVariable String id) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_BROADCAST);
        return BroadcastView.of(service.start(client, id), service.counts(id));
    }

    @PostMapping("/{id}/cancel")
    public BroadcastView cancel(HttpServletRequest http, @PathVariable String id) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_BROADCAST);
        return BroadcastView.of(service.cancel(client, id), service.counts(id));
    }

    @GetMapping("/{id}/recipients")
    public PageView<MessageView> recipients(HttpServletRequest http, @PathVariable String id,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "25") int size) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_BROADCAST);
        var result = service.recipients(client, id, status, page, size);
        List<MessageView> content = result.getContent().stream().map(MessageView::of).toList();
        return new PageView<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
