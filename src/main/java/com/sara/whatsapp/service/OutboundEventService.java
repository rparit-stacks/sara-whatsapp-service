package com.sara.whatsapp.service;

import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.OutboundEvent;
import com.sara.whatsapp.repo.OutboundEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/** Queues events for delivery to a client's callback URL, when the client has one. */
@Service
public class OutboundEventService {

    private final ClientService clients;
    private final OutboundEventRepository repository;

    public OutboundEventService(ClientService clients, OutboundEventRepository repository) {
        this.clients = clients;
        this.repository = repository;
    }

    public void publish(String clientId, String type, Map<String, Object> data) {
        if (clientId == null) {
            return;
        }
        boolean wantsEvents = clients.find(clientId)
                .map(ApiClient::getCallbackUrl)
                .filter(url -> !url.isBlank())
                .isPresent();
        if (!wantsEvents) {
            return;
        }
        OutboundEvent event = new OutboundEvent();
        event.setClientId(clientId);
        event.setType(type);
        event.setData(data);
        event.setStatus(OutboundEvent.Status.PENDING);
        event.setNextAttemptAt(Instant.now());
        event.setCreatedAt(Instant.now());
        repository.save(event);
    }
}
