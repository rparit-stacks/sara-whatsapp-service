package com.sara.whatsapp.provider.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.Template;
import com.sara.whatsapp.provider.HealthStatus;
import com.sara.whatsapp.provider.SendResult;
import com.sara.whatsapp.provider.WhatsAppProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Sends template messages through the WhatsApp Cloud API of Meta. */
@Component
public class MetaCloudProvider implements WhatsAppProvider {

    private static final Logger log = LoggerFactory.getLogger(MetaCloudProvider.class);

    private final WhatsAppProperties.Meta meta;
    private final ObjectMapper mapper;
    private final RestClient client;

    public MetaCloudProvider(WhatsAppProperties props, ObjectMapper mapper) {
        this.meta = props.getMeta();
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String name() {
        return "META";
    }

    @Override
    public SendResult send(Message message, Template template) {
        if (isBlank(meta.getAccessToken()) || isBlank(meta.getPhoneNumberId())) {
            return SendResult.permanent("NOT_CONFIGURED", "Meta access token or phone number id is not set");
        }
        String url = meta.getBaseUrl() + "/" + meta.getGraphVersion() + "/" + meta.getPhoneNumberId() + "/messages";
        try {
            ResponseEntity<String> response = client.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + meta.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MetaPayloadBuilder.build(message, template))
                    .retrieve()
                    .onStatus(status -> true, (request, resp) -> { })
                    .toEntity(String.class);

            int status = response.getStatusCode().value();
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode id = mapper.readTree(response.getBody()).path("messages").path(0).path("id");
                if (id.isTextual()) {
                    return SendResult.accepted(id.asText());
                }
                return SendResult.permanent("NO_MESSAGE_ID", "Meta accepted the request but returned no message id");
            }
            return MetaErrors.classify(status, response.getBody(), mapper);
        } catch (Exception e) {
            log.warn("Meta send failed for message {}: {}", message.getId(), e.getMessage());
            return SendResult.retryable("NETWORK", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public HealthStatus health() {
        if (isBlank(meta.getAccessToken()) || isBlank(meta.getPhoneNumberId())) {
            return new HealthStatus(false, "Meta access token or phone number id is not set");
        }
        String url = meta.getBaseUrl() + "/" + meta.getGraphVersion() + "/" + meta.getPhoneNumberId()
                + "?fields=display_phone_number,verified_name,quality_rating";
        try {
            ResponseEntity<String> response = client.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + meta.getAccessToken())
                    .retrieve()
                    .onStatus(status -> true, (request, resp) -> { })
                    .toEntity(String.class);
            boolean up = response.getStatusCode().is2xxSuccessful();
            return new HealthStatus(up, up ? "reachable" : "HTTP " + response.getStatusCode().value());
        } catch (Exception e) {
            return new HealthStatus(false, e.getClass().getSimpleName());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
