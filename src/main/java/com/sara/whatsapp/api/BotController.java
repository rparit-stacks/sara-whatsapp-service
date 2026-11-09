package com.sara.whatsapp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.sara.whatsapp.bot.BotFlowService;
import com.sara.whatsapp.bot.BotLicenceService;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.BotFlowDoc;
import com.sara.whatsapp.security.ApiAuth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The bot's workflow: read it, save a draft, publish. Every save is rebuilt from allowed fields; publish re-checks. */
@RestController
@RequestMapping("/v1/bot")
public class BotController {

    private final BotFlowService flows;
    private final BotLicenceService licence;

    public BotController(BotFlowService flows, BotLicenceService licence) {
        this.flows = flows;
        this.licence = licence;
    }

    @GetMapping("/flow")
    public Map<String, Object> get(HttpServletRequest http) {
        ApiAuth.require(http, ApiClient.SCOPE_READ);
        BotFlowDoc doc = flows.doc();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draft", flows.parse(doc.getDraft()));
        out.put("published", flows.parse(doc.getPublished()));
        out.put("publishedVersion", doc.getPublishedVersion());
        out.put("publishedAt", doc.getPublishedAt());
        out.put("updatedAt", doc.getUpdatedAt());
        out.put("issues", flows.issuesOfDraft());
        out.put("live", licence.isLive());
        return out;
    }

    @PutMapping("/flow/draft")
    public Map<String, Object> saveDraft(HttpServletRequest http, @RequestBody JsonNode body) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_TEMPLATES);
        BotFlowService.Draft d = flows.saveDraft(body, client.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flow", d.flow());
        out.put("issues", d.issues());
        out.put("dropped", d.dropped());
        return out;
    }

    @PostMapping("/flow/publish")
    public Map<String, Object> publish(HttpServletRequest http) {
        ApiClient client = ApiAuth.require(http, ApiClient.SCOPE_TEMPLATES);
        BotFlowDoc doc = flows.publish(client.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("publishedVersion", doc.getPublishedVersion());
        out.put("publishedAt", doc.getPublishedAt());
        out.put("live", licence.isLive());
        return out;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest http) {
        ApiAuth.require(http, ApiClient.SCOPE_READ);
        BotFlowDoc doc = flows.doc();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("live", licence.isLive());
        out.put("published", doc.getPublished() != null);
        out.put("publishedVersion", doc.getPublishedVersion());
        return out;
    }
}
