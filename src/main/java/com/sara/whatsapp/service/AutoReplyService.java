package com.sara.whatsapp.service;

import com.sara.whatsapp.api.dto.SendMessageRequest;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.AutoReplyLog;
import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.Priority;
import com.sara.whatsapp.repo.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Tells someone who writes to this number that it is not monitored. The first message starts a
 * cooldown (6 hours by default); messages inside it get no reply, the first one after it does.
 */
@Service
public class AutoReplyService {

    private static final Logger log = LoggerFactory.getLogger(AutoReplyService.class);

    private final WhatsAppProperties props;
    private final MongoTemplate mongo;
    private final ClientService clients;
    private final MessageService messages;
    private final TemplateRepository templates;

    public AutoReplyService(WhatsAppProperties props, MongoTemplate mongo, ClientService clients, MessageService messages,
                            TemplateRepository templates) {
        this.props = props;
        this.mongo = mongo;
        this.clients = clients;
        this.messages = messages;
        this.templates = templates;
    }

    public void replyTo(String from) {
        String templateKey = props.getAutoReply().getTemplateKey();
        if (templateKey == null || templateKey.isBlank() || from == null || from.isBlank()) {
            return;
        }
        ApiClient client = clients.list().stream()
                .filter(ApiClient::isActive)
                .filter(c -> c.getScopes() != null && c.getScopes().contains(ApiClient.SCOPE_SEND))
                .findFirst().orElse(null);
        if (client == null) {
            log.warn("Auto-reply skipped: no active API client with the send scope");
            return;
        }
        // The setting may hold our key or the name the template has at Meta.
        String wanted = templateKey.trim();
        String resolved = templates.findByKey(wanted)
                .or(() -> templates.findFirstByMetaName(wanted))
                .map(t -> t.getKey()).orElse(null);
        if (resolved == null) {
            log.warn("Auto-reply skipped: template '{}' not found", wanted);
            return;
        }
        Instant now = Instant.now();
        if (!claim(from, now)) {
            return;
        }
        try {
            messages.queue(client, new SendMessageRequest("auto-" + from + "-" + now.toEpochMilli(),
                    resolved, null, from, new MessageParameters(), Priority.NORMAL, now.plus(Duration.ofHours(1))));
        } catch (RuntimeException e) {
            log.warn("Auto-reply to {} failed: {}", from, e.getMessage());
            // Give the sender their reply next time instead of waiting out the cooldown.
            mongo.remove(Query.query(Criteria.where("_id").is(from)), AutoReplyLog.class);
        }
    }

    /** True for exactly one caller per sender per cooldown. */
    private boolean claim(String from, Instant now) {
        Instant cutoff = now.minus(Duration.ofHours(Math.max(1, props.getAutoReply().getCooldownHours())));
        Query due = Query.query(Criteria.where("_id").is(from).and("lastSentAt").lt(cutoff));
        try {
            // Updates the row when the cooldown is over; inserts when there is none; when the row is
            // still inside its cooldown the upsert collides on the id and nothing is claimed.
            mongo.findAndModify(due, new Update().set("lastSentAt", now),
                    FindAndModifyOptions.options().upsert(true), AutoReplyLog.class);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
