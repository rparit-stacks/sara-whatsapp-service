package com.sara.whatsapp.api;

import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.Template;
import com.sara.whatsapp.security.ApiAuth;
import com.sara.whatsapp.service.TemplateService;
import jakarta.servlet.http.HttpServletRequest;
import com.sara.whatsapp.provider.meta.MetaMediaClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/templates")
public class TemplateController {

    /** Fields a caller may set when registering a template by hand. */
    public record TemplateRequest(String metaName, String language, String category, String status,
                                  String parameterFormat, String headerType, List<String> bodyParameters) {
    }

    public record UpdateComponentsRequest(List<Map<String, Object>> components, String category) {
    }

    private final TemplateService service;
    private final MetaMediaClient media;

    public TemplateController(TemplateService service, MetaMediaClient media) {
        this.service = service;
        this.media = media;
    }

    /** Deletes the template here and in Meta. Use remote=false to remove only the local copy. */
    @DeleteMapping("/{key}")
    public Map<String, Object> delete(HttpServletRequest http, @PathVariable String key,
                                      @RequestParam(defaultValue = "true") boolean remote) {
        ApiAuth.require(http, ApiClient.SCOPE_TEMPLATES);
        service.delete(key, remote);
        return Map.of("deleted", key);
    }

    /** Edits a template's components in Meta. It goes back to review. */
    @PutMapping("/{key}/components")
    public Template updateComponents(HttpServletRequest http, @PathVariable String key,
                                     @RequestBody UpdateComponentsRequest request) {
        ApiAuth.require(http, ApiClient.SCOPE_TEMPLATES);
        return service.updateComponents(key, request.components(), request.category());
    }

    /**
     * Uploads a sample image, video or document and returns the handle to use as the example of a
     * media header when creating a template.
     */
    @PostMapping("/media")
    public Map<String, String> uploadMedia(HttpServletRequest http, @RequestParam("file") MultipartFile file) throws java.io.IOException {
        ApiAuth.require(http, ApiClient.SCOPE_TEMPLATES);
        if (file.isEmpty()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "file is empty");
        }
        String name = file.getOriginalFilename() == null ? "sample" : file.getOriginalFilename();
        String type = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        return Map.of("handle", media.uploadHandle(file.getBytes(), name, type));
    }

    @GetMapping
    public List<Template> list(HttpServletRequest http) {
        ApiAuth.require(http, ApiClient.SCOPE_READ);
        return service.list();
    }

    @GetMapping("/{key}")
    public Template get(HttpServletRequest http, @PathVariable String key) {
        ApiAuth.require(http, ApiClient.SCOPE_READ);
        return service.find(key).orElseThrow(() -> ApiException.notFound("Template not found"));
    }

    /** Creates a template in Meta (it then goes to review) and registers it here. */
    @PostMapping
    public Template create(HttpServletRequest http, @RequestBody TemplateService.CreateRequest request) {
        ApiAuth.require(http, ApiClient.SCOPE_TEMPLATES);
        return service.create(request);
    }

    /** Registers a template by hand, or updates one. */
    @PutMapping("/{key}")
    public Template put(HttpServletRequest http, @PathVariable String key, @RequestBody TemplateRequest request) {
        ApiAuth.require(http, ApiClient.SCOPE_TEMPLATES);
        Template input = new Template();
        input.setMetaName(request.metaName());
        input.setLanguage(request.language());
        input.setCategory(request.category());
        input.setStatus(request.status());
        input.setParameterFormat(request.parameterFormat());
        input.setHeaderType(request.headerType());
        input.setBodyParameters(request.bodyParameters());
        return service.upsert(key, input);
    }

    /** Pulls the templates and their approval status from Meta. */
    @PostMapping("/sync")
    public Map<String, Object> sync(HttpServletRequest http) {
        ApiAuth.require(http, ApiClient.SCOPE_TEMPLATES);
        return Map.of("synced", service.sync());
    }
}
