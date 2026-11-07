package com.sara.whatsapp.provider.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.config.WhatsAppProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads the templates of the WhatsApp Business Account from Meta. */
@Component
public class MetaTemplateClient {

    private final WhatsAppProperties.Meta meta;
    private final ObjectMapper mapper;
    private final RestClient client;

    public MetaTemplateClient(WhatsAppProperties props, ObjectMapper mapper) {
        this.meta = props.getMeta();
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** All templates of the account, following Meta's paging. */
    public List<JsonNode> fetchAll() {
        if (isBlank(meta.getAccessToken()) || isBlank(meta.getWabaId())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED",
                    "Meta access token or WhatsApp Business Account id is not set");
        }
        List<JsonNode> out = new ArrayList<>();
        String url = meta.getBaseUrl() + "/" + meta.getGraphVersion() + "/" + meta.getWabaId()
                + "/message_templates?limit=100&fields=id,name,language,status,category,quality_score,components";
        int pages = 0;
        while (url != null && pages++ < 50) {
            ResponseEntity<String> response = client.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + meta.getAccessToken())
                    .retrieve()
                    .onStatus(status -> true, (request, resp) -> { })
                    .toEntity(String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "META_ERROR",
                        "Meta returned HTTP " + response.getStatusCode().value() + " while listing templates");
            }
            try {
                JsonNode root = mapper.readTree(response.getBody());
                root.path("data").forEach(out::add);
                JsonNode next = root.path("paging").path("next");
                url = next.isTextual() ? next.asText() : null;
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "META_ERROR", "Meta returned an unreadable response");
            }
        }
        return out;
    }

    /** Creates a template in Meta. It then waits for review. Meta's error text is passed back on a rejection. */
    public JsonNode create(Map<String, Object> body) {
        return call(HttpMethod.POST, base() + "/" + meta.getWabaId() + "/message_templates", body);
    }

    /** Replaces the components of an existing template. The template goes back to review. */
    public JsonNode update(String metaTemplateId, Map<String, Object> body) {
        return call(HttpMethod.POST, base() + "/" + metaTemplateId, body);
    }

    /** Deletes a template (by id when known, which removes that one language only). */
    public JsonNode delete(String name, String metaTemplateId) {
        String url = base() + "/" + meta.getWabaId() + "/message_templates?name=" + name
                + (isBlank(metaTemplateId) ? "" : "&hsm_id=" + metaTemplateId);
        return call(HttpMethod.DELETE, url, null);
    }

    private String base() {
        return meta.getBaseUrl() + "/" + meta.getGraphVersion();
    }

    private JsonNode call(HttpMethod method, String url, Map<String, Object> body) {
        if (isBlank(meta.getAccessToken()) || isBlank(meta.getWabaId())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED",
                    "Meta access token or WhatsApp Business Account id is not set");
        }
        RestClient.RequestBodySpec request = client.method(method)
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + meta.getAccessToken());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> response = request.retrieve()
                .onStatus(status -> true, (req, resp) -> { })
                .toEntity(String.class);
        try {
            JsonNode root = mapper.readTree(response.getBody() == null || response.getBody().isBlank() ? "{}" : response.getBody());
            if (response.getStatusCode().is2xxSuccessful()) {
                return root;
            }
            JsonNode error = root.path("error");
            String detail = error.path("error_user_msg").asText(error.path("message").asText("Meta rejected the request"));
            HttpStatus status = response.getStatusCode().is4xxClientError() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
            throw new ApiException(status, "META_REJECTED", detail);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "META_ERROR", "Meta returned an unreadable response");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
