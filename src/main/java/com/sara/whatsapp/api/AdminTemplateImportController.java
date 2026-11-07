package com.sara.whatsapp.api;

import com.sara.whatsapp.provider.meta.MetaMediaClient;
import com.sara.whatsapp.service.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator-only bulk import of templates (X-Admin-Key, like everything under /v1/admin). It is not
 * part of the client API and is not reachable from the admin panel. Meant for moving templates
 * from another WhatsApp account into this one.
 */
@RestController
@RequestMapping("/v1/admin/templates")
public class AdminTemplateImportController {

    private static final Logger log = LoggerFactory.getLogger(AdminTemplateImportController.class);

    public record BulkRequest(List<TemplateService.BulkItem> templates, Boolean validate) {
    }

    private final TemplateService templates;
    private final MetaMediaClient media;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public AdminTemplateImportController(TemplateService templates, MetaMediaClient media) {
        this.templates = templates;
        this.media = media;
    }

    @PostMapping("/bulk")
    public List<TemplateService.BulkResult> bulk(@RequestBody BulkRequest request) {
        if (request == null || request.templates() == null || request.templates().isEmpty()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "templates is required");
        }
        if (request.templates().size() > 100) {
            throw ApiException.badRequest("VALIDATION_FAILED", "At most 100 templates per call");
        }
        List<TemplateService.BulkItem> prepared = new ArrayList<>();
        List<TemplateService.BulkResult> early = new ArrayList<>();
        for (TemplateService.BulkItem item : request.templates()) {
            try {
                prepared.add(new TemplateService.BulkItem(item.name(), item.language(), item.category(), withUploadedSamples(item.components())));
            } catch (RuntimeException e) {
                early.add(new TemplateService.BulkResult(item.name(), "failed", "Sample file: " + e.getMessage()));
            }
        }
        List<TemplateService.BulkResult> results = new ArrayList<>(early);
        results.addAll(templates.bulkCreate(prepared, request.validate() == null || request.validate()));
        return results;
    }

    /**
     * A template read back from Meta shows its header sample as a link. Creating one needs an
     * upload handle, so each media sample is downloaded and uploaded again.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> withUploadedSamples(List<Map<String, Object>> components) {
        if (components == null) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : components) {
            Map<String, Object> copy = new LinkedHashMap<>(c);
            String format = String.valueOf(c.get("format")).toUpperCase();
            if ("HEADER".equalsIgnoreCase(String.valueOf(c.get("type"))) && List.of("IMAGE", "VIDEO", "DOCUMENT").contains(format)
                    && c.get("example") instanceof Map<?, ?> example && example.get("header_handle") instanceof List<?> handles
                    && !handles.isEmpty() && String.valueOf(handles.get(0)).startsWith("http")) {
                String url = String.valueOf(handles.get(0));
                try {
                    HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() >= 400 || response.body().length == 0) {
                        throw new IllegalStateException("could not download the sample (HTTP " + response.statusCode() + ")");
                    }
                    String type = response.headers().firstValue("Content-Type")
                            .orElse(format.equals("DOCUMENT") ? "application/pdf" : format.equals("VIDEO") ? "video/mp4" : "image/jpeg");
                    String ext = type.contains("pdf") ? ".pdf" : type.contains("mp4") ? ".mp4" : type.contains("png") ? ".png" : ".jpg";
                    String handle = media.uploadHandle(response.body(), "sample" + ext, type.split(";")[0].trim());
                    Map<String, Object> newExample = new LinkedHashMap<>((Map<String, Object>) example);
                    newExample.put("header_handle", List.of(handle));
                    copy.put("example", newExample);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while downloading the sample");
                } catch (java.io.IOException e) {
                    log.warn("Sample download failed: {}", e.getMessage());
                    throw new IllegalStateException("could not download the sample: " + e.getMessage());
                }
            }
            out.add(copy);
        }
        return out;
    }
}
