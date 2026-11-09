package com.sara.whatsapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.domain.Template;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** A sync must not rewrite a template whose content did not change, even though Meta's sample links are different every time. */
class TemplateSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Template template(String status, String handle) throws Exception {
        Template t = new Template();
        t.setId("1");
        t.setKey("ai_menu");
        t.setMetaName("ai_menu");
        t.setLanguage("en");
        t.setStatus(status);
        TemplateService.applyComponents(t, mapper.readTree("""
                [{"type":"HEADER","format":"IMAGE","example":{"header_handle":["%s"]}},
                 {"type":"BODY","text":"Hi {{1}}","example":{"body_text":[["Asha"]]}}]""".formatted(handle)));
        return t;
    }

    @Test
    void aDifferentSignedSampleLinkIsNotAChange() throws Exception {
        Template a = template("APPROVED", "https://scontent.example/x.jpg?oh=111&oe=222");
        Template b = template("APPROVED", "https://scontent.example/x.jpg?oh=999&oe=888");
        assertEquals(TemplateService.snapshot(a), TemplateService.snapshot(b));
    }

    @Test
    void aRealChangeIsDetected() throws Exception {
        Template a = template("PENDING", "https://scontent.example/x.jpg?oh=1");
        Template b = template("APPROVED", "https://scontent.example/x.jpg?oh=1");
        assertNotEquals(TemplateService.snapshot(a), TemplateService.snapshot(b));

        Template c = template("APPROVED", "https://scontent.example/x.jpg?oh=1");
        TemplateService.applyComponents(c, mapper.readTree("[{\"type\":\"BODY\",\"text\":\"Hello {{1}} and {{2}}\"}]"));
        assertNotEquals(TemplateService.snapshot(b), TemplateService.snapshot(c));
    }

    @Test
    void theRecordsOwnTimesAreIgnored() throws Exception {
        Template a = template("APPROVED", "https://scontent.example/x.jpg");
        Template b = template("APPROVED", "https://scontent.example/x.jpg");
        b.setSyncedAt(Instant.now());
        b.setUpdatedAt(Instant.now());
        assertEquals(TemplateService.snapshot(a), TemplateService.snapshot(b));
    }
}
