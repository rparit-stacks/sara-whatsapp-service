package com.sara.whatsapp.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.config.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

/**
 * Asks the backend, about once a minute, whether the bot's Marketplace licence is live (trial with days of use left, or bought,
 * and switched on). If the backend cannot be reached the last good answer is trusted for a grace period, then the bot stops.
 */
@Service
public class BotLicenceService {

    private static final Logger log = LoggerFactory.getLogger(BotLicenceService.class);

    private final WhatsAppProperties.Bot bot;
    private final ObjectMapper mapper;
    private final RestClient client;

    private volatile boolean live;
    private volatile Instant lastOk = Instant.EPOCH;

    public BotLicenceService(WhatsAppProperties props, ObjectMapper mapper) {
        this.bot = props.getBot();
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(6));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** True when the bot may answer customers. */
    public boolean isLive() {
        if (!bot.isEnabled()) {
            return false;
        }
        if (!bot.isLicenceRequired()) {
            return true;
        }
        return live && Duration.between(lastOk, Instant.now()).toMinutes() < bot.getLicenceGraceMinutes();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 3_000)
    public void refresh() {
        if (!bot.isLicenceRequired()) {
            return;
        }
        try {
            String body = client.get().uri(bot.getBackendUrl().replaceAll("/+$", "") + "/api/marketplace/live-products").retrieve().body(String.class);
            JsonNode products = mapper.readTree(body).path("products");
            boolean now = false;
            for (JsonNode p : products) {
                if (bot.getProductKey().equals(p.asText())) {
                    now = true;
                }
            }
            live = now;
            lastOk = Instant.now();
        } catch (Exception e) {
            log.warn("Could not check the bot licence: {}", e.getMessage());
        }
    }
}
