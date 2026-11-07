package com.sara.whatsapp.worker;

import com.sara.whatsapp.service.TemplateService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Keeps the local template records in step with Meta, which is the source of truth. */
@Component
public class TemplateSyncJob {

    private final TemplateService templates;

    public TemplateSyncJob(TemplateService templates) {
        this.templates = templates;
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 30_000)
    public void refresh() {
        templates.refreshIfStale(Duration.ofSeconds(25));
    }
}
