package com.sara.whatsapp.worker;

import com.sara.whatsapp.service.TemplateService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Keeps the local template records in step with Meta, which is the source of truth. It syncs once shortly after start and then only as a
 * slow safety net (every 30 minutes by default). The real updates come from three other places: Meta's status webhook, a sync whenever
 * the templates list is opened, and the Sync button.
 */
@Component
public class TemplateSyncJob {

    private final TemplateService templates;

    public TemplateSyncJob(TemplateService templates) {
        this.templates = templates;
    }

    @Scheduled(initialDelay = 5_000, fixedDelayString = "${whatsapp.templates.sync-interval-ms:1800000}")
    public void refresh() {
        templates.refreshIfStale(Duration.ofMinutes(25));
    }
}
