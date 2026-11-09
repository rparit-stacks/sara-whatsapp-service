package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/** A Meta template the service knows about. Any number, defined by the client. */
@Getter
@Setter
@NoArgsConstructor
@Document("templates")
@CompoundIndex(name = "ix_meta_name_language", def = "{'metaName': 1, 'language': 1}")
public class Template {

    @Id
    private String id;

    /** The name callers use. */
    @Indexed(unique = true)
    private String key;

    /** The template name in Meta. */
    private String metaName;
    private String language;
    /** AUTHENTICATION, UTILITY or MARKETING. */
    private String category;
    /** APPROVED, PENDING, REJECTED, PAUSED, DISABLED ... or null when not known yet. */
    private String status;
    private String quality;

    /** POSITIONAL ({{1}}) or NAMED ({{first_name}}). */
    private String parameterFormat = "POSITIONAL";
    /** TEXT, IMAGE, VIDEO, DOCUMENT or null when the template has no media header. */
    private String headerType;
    /** Body placeholders in order: "1","2" or the names. Empty when the body has none. */
    private List<String> bodyParameters = List.of();

    /** The template's content as Meta holds it: header, body, footer and buttons (with examples). */
    private List<java.util.Map<String, Object>> components = List.of();
    @Indexed(sparse = true)
    private String metaTemplateId;

    private Instant syncedAt;
    /** Fingerprint of Meta's last answer for this template, so an unchanged template is not rewritten at every sync. */
    private String syncHash;
    private Instant createdAt;
    private Instant updatedAt;
}
