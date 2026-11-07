package com.sara.whatsapp.api;

import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.RateCard;
import com.sara.whatsapp.domain.MessageStatus;
import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.OutboundEvent;
import com.sara.whatsapp.service.UsageService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.domain.Sort;
import java.time.Duration;
import java.time.Instant;
import com.sara.whatsapp.provider.HealthStatus;
import com.sara.whatsapp.provider.ProviderRegistry;
import com.sara.whatsapp.service.ClientService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Operator endpoints, protected by X-Admin-Key. */
@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    public record CreateClientRequest(String name, Set<String> scopes, String callbackUrl) {
    }

    public record ProviderRequest(String provider) {
    }

    public record RatesRequest(String currency, Map<String, Double> rates) {
    }

    private final ClientService clients;
    private final ProviderRegistry providers;
    private final UsageService usage;
    private final MongoTemplate mongo;

    public AdminController(ClientService clients, ProviderRegistry providers, UsageService usage, MongoTemplate mongo) {
        this.clients = clients;
        this.providers = providers;
        this.usage = usage;
        this.mongo = mongo;
    }

    @GetMapping("/rates")
    public RateCard rates() {
        return usage.rateCard();
    }

    /** Sets the price per delivered message, e.g. {"currency":"INR","rates":{"MARKETING:91":0.78,"UTILITY:*":0.2}}. */
    @PutMapping("/rates")
    public RateCard saveRates(@RequestBody RatesRequest request) {
        return usage.saveRateCard(request.currency(), request.rates(), "admin");
    }

    /** Queue depth and backlog, for a quick health check. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeProvider", providers.activeName());
        out.put("queued", mongo.count(new Query(Criteria.where("status").is(MessageStatus.QUEUED).and("nextAttemptAt").ne(null)), Message.class));
        out.put("sending", mongo.count(new Query(Criteria.where("status").is(MessageStatus.SENDING)), Message.class));
        Message oldest = mongo.findOne(new Query(Criteria.where("status").is(MessageStatus.QUEUED).and("nextAttemptAt").ne(null))
                .with(Sort.by("createdAt")), Message.class);
        out.put("oldestQueuedSeconds", oldest == null || oldest.getCreatedAt() == null ? 0
                : Duration.between(oldest.getCreatedAt(), Instant.now()).toSeconds());
        out.put("failedLast24h", mongo.count(new Query(Criteria.where("status").is(MessageStatus.FAILED)
                .and("updatedAt").gte(Instant.now().minusSeconds(86400))), Message.class));
        out.put("pendingEvents", mongo.count(new Query(Criteria.where("status").is(OutboundEvent.Status.PENDING)), OutboundEvent.class));
        out.put("failedEvents", mongo.count(new Query(Criteria.where("status").is(OutboundEvent.Status.FAILED)), OutboundEvent.class));
        return out;
    }

    /** Creates an API client. The key and the callback secret are shown only in this response. */
    @PostMapping("/clients")
    public Map<String, Object> createClient(@RequestBody CreateClientRequest request) {
        ClientService.CreatedClient created = clients.create(request.name(), request.scopes(), request.callbackUrl());
        Map<String, Object> out = new LinkedHashMap<>(view(created.client()));
        out.put("apiKey", created.apiKey());
        out.put("callbackSecret", created.callbackSecret());
        return out;
    }

    @GetMapping("/clients")
    public List<Map<String, Object>> listClients() {
        return clients.list().stream().map(AdminController::view).toList();
    }

    @GetMapping("/provider")
    public Map<String, Object> provider() {
        return Map.of("active", providers.activeName(), "available", providers.names());
    }

    @PutMapping("/provider")
    public Map<String, Object> switchProvider(@RequestBody ProviderRequest request) {
        providers.setActive(request.provider(), "admin");
        return provider();
    }

    @GetMapping("/provider/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : providers.names()) {
            HealthStatus status = providers.get(name).health();
            out.put(name, Map.of("up", status.up(), "detail", status.detail()));
        }
        return out;
    }

    private static Map<String, Object> view(ApiClient c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("name", c.getName());
        out.put("keyPrefix", c.getKeyPrefix());
        out.put("scopes", c.getScopes());
        out.put("callbackUrl", c.getCallbackUrl());
        out.put("active", c.isActive());
        out.put("createdAt", c.getCreatedAt());
        return out;
    }
}
