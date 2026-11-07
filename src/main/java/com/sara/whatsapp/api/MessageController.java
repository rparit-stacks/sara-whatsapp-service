package com.sara.whatsapp.api;

import com.sara.whatsapp.api.dto.MessageView;
import com.sara.whatsapp.api.dto.PageView;
import com.sara.whatsapp.api.dto.SendMessageRequest;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageEvent;
import com.sara.whatsapp.repo.MessageEventRepository;
import com.sara.whatsapp.security.ApiAuth;
import com.sara.whatsapp.service.MessageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/v1/messages")
public class MessageController {

    private final MessageService service;
    private final MessageEventRepository events;

    public MessageController(MessageService service, MessageEventRepository events) {
        this.service = service;
        this.events = events;
    }

    /** Queues a template message. 202 when new, 200 with the original message when the clientMessageId was seen before. */
    @PostMapping
    public ResponseEntity<MessageView> send(HttpServletRequest http, @Valid @RequestBody SendMessageRequest request) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_SEND);
        MessageService.QueueResult result = service.queue(client, request);
        if (result.duplicate()) {
            return ResponseEntity.ok().header("X-Idempotent-Replay", "true").body(MessageView.of(result.message()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(MessageView.of(result.message()));
    }

    @GetMapping("/{id}")
    public MessageView get(HttpServletRequest http, @PathVariable String id) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_READ);
        return MessageView.of(service.find(client, id).orElseThrow(() -> ApiException.notFound("Message not found")));
    }

    @GetMapping
    public PageView<MessageView> list(
            HttpServletRequest http,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String event,
            @RequestParam(required = false) String templateKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_READ);
        var result = service.list(client, new MessageService.Filter(status, to, event, templateKey, from, until, q, sort, dir), page, size);
        List<MessageView> content = result.getContent().stream().map(MessageView::of).toList();
        return new PageView<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{id}/events")
    public List<MessageEvent> history(HttpServletRequest http, @PathVariable String id) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_READ);
        Message message = service.find(client, id).orElseThrow(() -> ApiException.notFound("Message not found"));
        return events.findByMessageIdOrderByAtAsc(message.getId());
    }

    @PostMapping("/{id}/retry")
    public MessageView retry(HttpServletRequest http, @PathVariable String id) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_SEND);
        return MessageView.of(service.retry(client, id));
    }
}
