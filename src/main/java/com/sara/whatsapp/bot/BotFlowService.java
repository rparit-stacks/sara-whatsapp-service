package com.sara.whatsapp.bot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.domain.BotFlowDoc;
import com.sara.whatsapp.repo.BotFlowRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/** Stores the bot's workflow. Nothing is saved or published unless it passes the same rules the builder shows. */
@Service
public class BotFlowService {

    public record Draft(ObjectNode flow, List<BotFlowValidator.Issue> issues, List<String> dropped) {
    }

    private static final Duration CACHE = Duration.ofSeconds(5);

    private final BotFlowRepository repository;
    private final BotFlowValidator validator;
    private final ObjectMapper mapper;

    private volatile ObjectNode cachedPublished;
    private volatile Instant cachedAt = Instant.EPOCH;

    public BotFlowService(BotFlowRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
        this.validator = new BotFlowValidator(mapper);
    }

    public BotFlowDoc doc() {
        return repository.findById(BotFlowDoc.ID).orElseGet(BotFlowDoc::new);
    }

    public ObjectNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(json);
            return n instanceof ObjectNode o ? o : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public Draft saveDraft(JsonNode input, String actor) {
        BotFlowValidator.Sanitized s = validator.sanitize(input);
        if (s.flow() == null) {
            throw ApiException.badRequest("FLOW_INVALID", s.dropped().isEmpty() ? "This is not a flow." : s.dropped().get(0));
        }
        BotFlowDoc doc = doc();
        doc.setDraft(s.flow().toString());
        doc.setUpdatedAt(Instant.now());
        doc.setUpdatedBy(actor);
        repository.save(doc);
        return new Draft(s.flow(), validator.validate(s.flow()), s.dropped());
    }

    public List<BotFlowValidator.Issue> issuesOfDraft() {
        ObjectNode draft = parse(doc().getDraft());
        return draft == null ? List.of() : validator.validate(draft);
    }

    /** Publishes the saved draft after checking it again on the server. A flow with errors is never published. */
    public BotFlowDoc publish(String actor) {
        BotFlowDoc doc = doc();
        ObjectNode draft = parse(doc.getDraft());
        if (draft == null) {
            throw ApiException.unprocessable("NO_DRAFT", "There is no draft to publish. Save the flow first.");
        }
        BotFlowValidator.Sanitized s = validator.sanitize(draft);
        List<BotFlowValidator.Issue> issues = validator.validate(s.flow());
        if (BotFlowValidator.hasErrors(issues)) {
            String first = issues.stream().filter(i -> i.level().equals("error")).limit(3).map(BotFlowValidator.Issue::text).collect(Collectors.joining(" "));
            throw ApiException.unprocessable("FLOW_HAS_ERRORS", "Fix these before publishing: " + first);
        }
        doc.setPublished(s.flow().toString());
        doc.setPublishedVersion(doc.getPublishedVersion() + 1);
        doc.setPublishedAt(Instant.now());
        doc.setPublishedBy(actor);
        repository.save(doc);
        cachedPublished = null;
        cachedAt = Instant.EPOCH;
        return doc;
    }

    /** The flow the bot runs, or null if nothing is published. Cached for a few seconds. */
    public ObjectNode published() {
        if (Duration.between(cachedAt, Instant.now()).compareTo(CACHE) > 0) {
            cachedPublished = parse(doc().getPublished());
            cachedAt = Instant.now();
        }
        return cachedPublished;
    }
}
