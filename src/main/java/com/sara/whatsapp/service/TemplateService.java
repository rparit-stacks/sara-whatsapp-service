package com.sara.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.domain.Template;
import com.sara.whatsapp.provider.meta.MetaTemplateClient;
import com.sara.whatsapp.repo.EventBindingRepository;
import com.sara.whatsapp.repo.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Templates the service can send. Any number, registered by hand or synced from Meta. */
@Service
public class TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");

    private final TemplateRepository repository;
    private final MetaTemplateClient metaClient;
    private final ObjectMapper mapper;
    private final EventBindingRepository bindings;

    public TemplateService(TemplateRepository repository, MetaTemplateClient metaClient, ObjectMapper mapper,
                           EventBindingRepository bindings) {
        this.repository = repository;
        this.metaClient = metaClient;
        this.mapper = mapper;
        this.bindings = bindings;
    }

    /**
     * Deletes a template here and, unless {@code remote} is false, in Meta too. A template that an
     * event binding still uses is not deleted.
     */
    public void delete(String key, boolean remote) {
        Template template = repository.findByKey(key).orElseThrow(() -> ApiException.notFound("Template not found"));
        List<String> using = bindings.findAll().stream()
                .filter(b -> key.equals(b.getTemplateKey())).map(b -> b.getEvent()).toList();
        if (!using.isEmpty()) {
            throw ApiException.unprocessable("IN_USE", "Template is used by event bindings: " + using);
        }
        if (remote) {
            metaClient.delete(template.getMetaName(), template.getMetaTemplateId());
        }
        repository.delete(template);
    }

    /** Replaces a template's components in Meta (it goes back to review) and refreshes the local copy. */
    public Template updateComponents(String key, List<Map<String, Object>> components, String category) {
        Template template = repository.findByKey(key).orElseThrow(() -> ApiException.notFound("Template not found"));
        if (isBlank(template.getMetaTemplateId())) {
            throw ApiException.unprocessable("NOT_IN_META", "This template has no Meta id yet. Sync from Meta first.");
        }
        if (components == null || components.isEmpty()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "components are required");
        }
        requireValid(template.getMetaName(), template.getLanguage(), isBlank(category) ? template.getCategory() : category, components);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("components", components);
        if (!isBlank(category)) {
            body.put("category", category.toUpperCase());
        }
        metaClient.update(template.getMetaTemplateId(), body);
        applyComponents(template, mapper.valueToTree(components));
        if (!isBlank(category)) {
            template.setCategory(category.toUpperCase());
        }
        template.setStatus("PENDING");
        template.setUpdatedAt(Instant.now());
        return repository.save(template);
    }

    /** What a caller gives to create a template in Meta. Components are passed to Meta as given. */
    public record CreateRequest(String key, String name, String language, String category,
                                String parameterFormat, List<Map<String, Object>> components) {
    }

    /**
     * Creates the template in Meta and registers it here, waiting for review. The components
     * follow Meta's template format (header, body, footer, buttons, with example values).
     */
    public Template create(CreateRequest request) {
        return create(request, true);
    }

    /** One entry of a bulk import. */
    public record BulkItem(String name, String language, String category, List<Map<String, Object>> components) {
    }

    /** Result for one entry: created, skipped (already there) or failed (with the reason). */
    public record BulkResult(String name, String outcome, String message) {
    }

    /**
     * Creates many templates in one go, one after another. An entry that already exists here is
     * skipped, and one that fails does not stop the others. With {@code validate} off, the
     * service's own content rules are skipped (for text Meta already approved elsewhere); Meta
     * still reviews every template.
     */
    public List<BulkResult> bulkCreate(List<BulkItem> items, boolean validate) {
        refreshIfStale(java.time.Duration.ZERO);
        List<BulkResult> results = new java.util.ArrayList<>();
        for (BulkItem item : items == null ? List.<BulkItem>of() : items) {
            String name = item.name();
            try {
                if (name != null && repository.findByKey(name).isPresent()) {
                    results.add(new BulkResult(name, "skipped", "Already exists"));
                    continue;
                }
                Template created = create(new CreateRequest(name, name, item.language(), item.category(),
                        isAuth(item.category()) ? null : "POSITIONAL", item.components()), validate);
                results.add(new BulkResult(name, "created", "Status " + created.getStatus() + ", type " + created.getCategory()));
            } catch (RuntimeException e) {
                results.add(new BulkResult(name, "failed", e.getMessage()));
            }
        }
        return results;
    }

    private static boolean isAuth(String category) {
        return category != null && "AUTHENTICATION".equalsIgnoreCase(category);
    }

    public Template create(CreateRequest request, boolean validate) {
        if (request.key() == null || !KEY.matcher(request.key()).matches()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "key may use letters, digits, '_', '-' and '.' (max 100)");
        }
        if (repository.findByKey(request.key()).isPresent()) {
            throw ApiException.unprocessable("KEY_EXISTS", "A template with this key already exists");
        }
        if (isBlank(request.name()) || isBlank(request.language()) || isBlank(request.category())
                || request.components() == null || request.components().isEmpty()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "name, language, category and components are required");
        }
        if (validate) {
            requireValid(request.name(), request.language(), request.category(), request.components());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", request.name());
        body.put("language", request.language());
        body.put("category", request.category().toUpperCase());
        if (!isBlank(request.parameterFormat())) {
            body.put("parameter_format", request.parameterFormat());
        }
        body.put("components", request.components());

        JsonNode result = metaClient.create(body);

        Template template = new Template();
        template.setKey(request.key());
        template.setMetaName(request.name());
        template.setLanguage(request.language());
        template.setCategory(result.path("category").asText(request.category().toUpperCase()));
        template.setStatus(result.path("status").asText("PENDING"));
        template.setMetaTemplateId(result.path("id").asText(null));
        applyComponents(template, mapper.valueToTree(request.components()));
        template.setCreatedAt(Instant.now());
        template.setUpdatedAt(Instant.now());
        return repository.save(template);
    }

    private static void requireValid(String name, String language, String category, List<Map<String, Object>> components) {
        List<String> problems = TemplateRules.validate(name, language, category, components);
        if (!problems.isEmpty()) {
            throw ApiException.unprocessable("TEMPLATE_INVALID", String.join("; ", problems));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private volatile Instant lastSync = Instant.EPOCH;

    /**
     * The local records mirror Meta. They are refreshed once at start, by Meta's status webhook, when the list is read and is more than
     * a minute old, and by a slow background check every 30 minutes, so a caller does not see a template that is gone from Meta.
     */
    public synchronized void refreshIfStale(java.time.Duration maxAge) {
        if (java.time.Duration.between(lastSync, Instant.now()).compareTo(maxAge) < 0) {
            return;
        }
        try {
            sync();
            lastSync = Instant.now();
        } catch (RuntimeException e) {
            // Meta unreachable or not configured: keep serving the last good copy.
            log.warn("Template refresh from Meta failed: {}", e.getMessage());
        }
    }

    public List<Template> list() {
        refreshIfStale(java.time.Duration.ofSeconds(60));
        return repository.findAll();
    }

    public Optional<Template> find(String key) {
        Optional<Template> found = repository.findByKey(key);
        if (found.isEmpty()) {
            refreshIfStale(java.time.Duration.ofSeconds(30));
            found = repository.findByKey(key);
        }
        return found;
    }

    /** Registers or updates a template by hand. Fields left null keep their current value. */
    public Template upsert(String key, Template input) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "key may use letters, digits, '_', '-' and '.' (max 100)");
        }
        Template template = repository.findByKey(key).orElseGet(() -> {
            Template t = new Template();
            t.setKey(key);
            t.setCreatedAt(Instant.now());
            return t;
        });
        if (input.getMetaName() != null) template.setMetaName(input.getMetaName());
        if (input.getLanguage() != null) template.setLanguage(input.getLanguage());
        if (input.getCategory() != null) template.setCategory(input.getCategory().toUpperCase());
        if (input.getStatus() != null) template.setStatus(input.getStatus().toUpperCase());
        if (input.getParameterFormat() != null) template.setParameterFormat(input.getParameterFormat().toUpperCase());
        if (input.getHeaderType() != null) template.setHeaderType(input.getHeaderType().toUpperCase());
        if (input.getBodyParameters() != null && !input.getBodyParameters().isEmpty()) {
            template.setBodyParameters(List.copyOf(input.getBodyParameters()));
        }
        if (template.getMetaName() == null || template.getMetaName().isBlank()
                || template.getLanguage() == null || template.getLanguage().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "metaName and language are required");
        }
        template.setUpdatedAt(Instant.now());
        return repository.save(template);
    }

    /** Pulls every template of the account from Meta and creates or updates the local copies. */
    public int sync() {
        int count = 0;
        int changed = 0;
        java.util.List<String> changedNames = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode node : metaClient.fetchAll()) {
            String name = node.path("name").asText("");
            String language = node.path("language").asText("");
            if (name.isBlank() || language.isBlank()) {
                continue;
            }
            Template template = repository.findByMetaTemplateId(node.path("id").asText(""))
                    .or(() -> repository.findByMetaNameAndLanguage(name, language))
                    .orElseGet(() -> newTemplate(name, language));
            com.fasterxml.jackson.databind.node.ObjectNode before = snapshot(template);
            template.setMetaTemplateId(node.path("id").asText(null));
            template.setMetaName(name);
            template.setLanguage(language);
            template.setCategory(node.path("category").asText(null));
            template.setStatus(node.path("status").asText(null));
            template.setQuality(node.path("quality_score").path("score").asText(null));
            applyComponents(template, node.path("components"));
            // Nothing we keep has changed, and the record was refreshed recently: leave it alone, no write.
            boolean recent = template.getSyncedAt() != null && Duration.between(template.getSyncedAt(), Instant.now()).compareTo(REFRESH_AFTER) < 0;
            if (template.getId() != null && recent && before.equals(snapshot(template))) {
                seen.add(template.getId());
                count++;
                continue;
            }
            changed++;
            if (changedNames.size() < 8) {
                changedNames.add(name);
            }
            template.setSyncedAt(Instant.now());
            template.setUpdatedAt(Instant.now());
            repository.save(template);
            seen.add(template.getId());
            count++;
        }
        pruneMissing(seen);
        lastSync = Instant.now();
        if (changed > 0) {
            log.info("Synced {} templates from Meta ({} new or changed: {}{})", count, changed, String.join(", ", changedNames), changed > changedNames.size() ? ", ..." : "");
        } else {
            log.debug("Synced {} templates from Meta, none changed", count);
        }
        return count;
    }

    /** A template that has not changed is still rewritten this often, to refresh the temporary sample links Meta gives for media headers. */
    private static final Duration REFRESH_AFTER = Duration.ofHours(6);
    private static final ObjectMapper SNAPSHOT = new ObjectMapper().findAndRegisterModules();

    /**
     * What we keep about a template, as plain data, for telling whether a sync changed anything. The record's own bookkeeping times are
     * left out, and so are Meta's sample header links (header_handle), which are signed, expiring URLs that differ on every request.
     */
    static com.fasterxml.jackson.databind.node.ObjectNode snapshot(Template t) {
        com.fasterxml.jackson.databind.node.ObjectNode n = SNAPSHOT.valueToTree(t);
        n.remove(List.of("id", "syncedAt", "createdAt", "updatedAt"));
        stripSampleLinks(n);
        return n;
    }

    private static void stripSampleLinks(JsonNode node) {
        if (node.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("header_handle");
            node.forEach(TemplateService::stripSampleLinks);
        } else if (node.isArray()) {
            node.forEach(TemplateService::stripSampleLinks);
        }
    }

    /**
     * Drops local copies of templates that came from Meta but are no longer in this account (for
     * example after switching to another WhatsApp account). Templates registered by hand, and
     * templates an event binding still uses, are kept.
     */
    private void pruneMissing(java.util.Set<String> seen) {
        java.util.Set<String> bound = new java.util.HashSet<>();
        bindings.findAll().forEach(b -> bound.add(b.getTemplateKey()));
        int removed = 0;
        for (Template t : repository.findAll()) {
            boolean fromMeta = t.getMetaTemplateId() != null && !t.getMetaTemplateId().isBlank();
            if (fromMeta && !seen.contains(t.getId()) && !bound.contains(t.getKey())) {
                repository.delete(t);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Removed {} local templates that are no longer in the Meta account", removed);
        }
    }

    /** From the message_template_status_update webhook. */
    public void applyStatusUpdate(String metaTemplateId, String name, String language, String status) {
        Optional<Template> found = metaTemplateId != null && !metaTemplateId.isBlank()
                ? repository.findByMetaTemplateId(metaTemplateId) : Optional.empty();
        if (found.isEmpty() && name != null && language != null) {
            found = repository.findByMetaNameAndLanguage(name, language);
        }
        found.ifPresentOrElse(t -> {
            t.setStatus(status == null ? null : status.toUpperCase());
            t.setUpdatedAt(Instant.now());
            repository.save(t);
        }, () -> log.info("Template status update for unknown template {} ({})", name, metaTemplateId));
    }

    private Template newTemplate(String name, String language) {
        Template t = new Template();
        String key = name;
        if (repository.findByKey(key).isPresent()) {
            key = name + "_" + language;
        }
        t.setKey(key);
        t.setCreatedAt(Instant.now());
        return t;
    }

    /** Reads the header format and the body placeholders from Meta's component list. */
    private static final ObjectMapper PLAIN = new ObjectMapper();

    static void applyComponents(Template template, JsonNode components) {
        try {
            template.setComponents(PLAIN.convertValue(components, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { }));
        } catch (IllegalArgumentException e) {
            template.setComponents(List.of());
        }
        template.setHeaderType(null);
        template.setBodyParameters(List.of());
        template.setParameterFormat("POSITIONAL");
        for (JsonNode component : components) {
            String type = component.path("type").asText("");
            if ("HEADER".equalsIgnoreCase(type)) {
                String format = component.path("format").asText("");
                if (!format.isBlank() && !"TEXT".equalsIgnoreCase(format)) {
                    template.setHeaderType(format.toUpperCase());
                }
            } else if ("BODY".equalsIgnoreCase(type)) {
                Set<String> names = new LinkedHashSet<>();
                Matcher matcher = PLACEHOLDER.matcher(component.path("text").asText(""));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
                boolean positional = names.stream().allMatch(n -> n.chars().allMatch(Character::isDigit));
                template.setParameterFormat(positional ? "POSITIONAL" : "NAMED");
                template.setBodyParameters(new ArrayList<>(names));
            }
        }
    }
}
