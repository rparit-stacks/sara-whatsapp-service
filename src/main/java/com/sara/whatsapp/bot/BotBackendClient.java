package com.sara.whatsapp.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.config.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The backend calls the bot needs: which event a message fits (AI), an answer from our knowledge (AI), the order status, a team alert. */
@Component
public class BotBackendClient {

    private static final Logger log = LoggerFactory.getLogger(BotBackendClient.class);

    public record Choice(String id, double confidence) {
    }

    public record OrderStatus(boolean found, String orderNumber, String status, String placedOn) {
    }

    private final WhatsAppProperties.Bot bot;
    private final ObjectMapper mapper;
    private final RestClient client;

    public BotBackendClient(WhatsAppProperties props, ObjectMapper mapper) {
        this.bot = props.getBot();
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(12));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    private String url(String path) {
        return bot.getBackendUrl().replaceAll("/+$", "") + path;
    }

    private boolean configured() {
        return bot.getSharedSecret() != null && !bot.getSharedSecret().isBlank();
    }

    /** Empty means the AI could not choose (not configured, capped, down, or nothing fits): the caller falls back. */
    public Optional<List<Choice>> route(String message, List<Map<String, Object>> events) {
        if (!configured()) {
            return Optional.empty();
        }
        try {
            String body = client.post().uri(url("/api/bot/route")).header("X-Bot-Secret", bot.getSharedSecret())
                    .body(Map.of("message", message, "events", events)).retrieve().body(String.class);
            JsonNode choices = mapper.readTree(body).path("choices");
            List<Choice> out = new ArrayList<>();
            for (JsonNode c : choices) {
                out.add(new Choice(c.path("id").asText(""), c.path("confidence").asDouble(0)));
            }
            return Optional.of(out);
        } catch (Exception e) {
            log.warn("Bot routing call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public String answer(String question, List<String> scopes) {
        if (!configured()) {
            return "";
        }
        try {
            String body = client.post().uri(url("/api/bot/answer")).header("X-Bot-Secret", bot.getSharedSecret())
                    .body(Map.of("question", question, "scopes", scopes)).retrieve().body(String.class);
            return mapper.readTree(body).path("text").asText("");
        } catch (Exception e) {
            log.warn("Bot answer call failed: {}", e.getMessage());
            return "";
        }
    }

    public OrderStatus orderStatus(String phone) {
        if (!configured()) {
            return new OrderStatus(false, null, null, null);
        }
        try {
            String body = client.get().uri(url("/api/bot/order-status?phone=" + phone)).header("X-Bot-Secret", bot.getSharedSecret()).retrieve().body(String.class);
            JsonNode n = mapper.readTree(body);
            return new OrderStatus(n.path("found").asBoolean(false), n.path("orderNumber").asText(null), n.path("status").asText(null), n.path("placedOn").asText(null));
        } catch (Exception e) {
            log.warn("Bot order lookup failed: {}", e.getMessage());
            return new OrderStatus(false, null, null, null);
        }
    }

    public void notifyTeam(String phone, String text) {
        if (!configured()) {
            return;
        }
        try {
            client.post().uri(url("/api/bot/notify")).header("X-Bot-Secret", bot.getSharedSecret())
                    .body(Map.of("phone", phone, "text", text == null ? "" : text)).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Bot team alert failed: {}", e.getMessage());
        }
    }
}
