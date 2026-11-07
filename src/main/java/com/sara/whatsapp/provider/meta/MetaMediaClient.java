package com.sara.whatsapp.provider.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.config.WhatsAppProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Uploads a sample file with Meta's Resumable Upload API and returns the handle that a template
 * with an image, video or document header needs as its example.
 */
@Component
public class MetaMediaClient {

    private final WhatsAppProperties.Meta meta;
    private final ObjectMapper mapper;
    private final RestClient client;

    public MetaMediaClient(WhatsAppProperties props, ObjectMapper mapper) {
        this.meta = props.getMeta();
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public String uploadHandle(byte[] content, String filename, String contentType) {
        if (blank(meta.getAccessToken()) || blank(meta.getAppId())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED",
                    "META_ACCESS_TOKEN and META_APP_ID are needed to upload media");
        }
        String base = meta.getBaseUrl() + "/" + meta.getGraphVersion();

        // Step 1: open an upload session.
        JsonNode session = read(client.post()
                .uri(base + "/" + meta.getAppId() + "/uploads?file_name={name}&file_length={length}&file_type={type}",
                        filename, content.length, contentType)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + meta.getAccessToken())
                .retrieve()
                .onStatus(status -> true, (req, resp) -> { })
                .toEntity(String.class));
        String sessionId = session.path("id").asText("");
        if (sessionId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "META_ERROR", "Meta did not open an upload session");
        }

        // Step 2: send the bytes. Meta answers with the handle in "h".
        JsonNode uploaded = read(client.post()
                .uri(base + "/" + sessionId)
                .header(HttpHeaders.AUTHORIZATION, "OAuth " + meta.getAccessToken())
                .header("file_offset", "0")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> { })
                .toEntity(String.class));
        String handle = uploaded.path("h").asText("");
        if (handle.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "META_ERROR", "Meta did not return a media handle");
        }
        return handle;
    }

    private JsonNode read(ResponseEntity<String> response) {
        try {
            JsonNode root = mapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            if (!response.getStatusCode().is2xxSuccessful()) {
                String detail = root.path("error").path("message").asText("Meta rejected the upload");
                throw new ApiException(response.getStatusCode().is4xxClientError() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY,
                        "META_REJECTED", detail);
            }
            return root;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "META_ERROR", "Meta returned an unreadable response");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
