package com.sara.whatsapp.bot;

import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.BotConversation;
import com.sara.whatsapp.repo.BotConversationRepository;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Per-customer chat state: the 24-hour window, the daily caps, and "a person has this chat". */
@Service
public class BotConversationService {

    /** WhatsApp lets a business send free-form messages for 24 hours after the customer's last message. */
    public static final Duration WINDOW = Duration.ofHours(24);

    private final MongoTemplate mongo;
    private final BotConversationRepository repository;
    private final ZoneId zone;

    public BotConversationService(MongoTemplate mongo, BotConversationRepository repository, WhatsAppProperties props) {
        this.mongo = mongo;
        this.repository = repository;
        this.zone = ZoneId.of(props.getUsage().getZone());
    }

    /** Records that the customer wrote. Returns true when this is the first time we have heard from them. */
    public boolean onInbound(String phone, Instant at) {
        BotConversation before = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(phone)),
                new Update().setOnInsert("firstSeenAt", at).max("lastInboundAt", at),
                FindAndModifyOptions.options().upsert(true).returnNew(false),
                BotConversation.class);
        return before == null;
    }

    public boolean windowOpen(String phone, Instant now) {
        return repository.findById(phone)
                .map(c -> c.getLastInboundAt() != null && Duration.between(c.getLastInboundAt(), now).compareTo(WINDOW) < 0)
                .orElse(false);
    }

    public boolean botPaused(String phone, Instant now) {
        return repository.findById(phone).map(c -> c.getHumanUntil() != null && c.getHumanUntil().isAfter(now)).orElse(false);
    }

    public void pauseBot(String phone, Instant until) {
        mongo.upsert(Query.query(Criteria.where("_id").is(phone)), new Update().set("humanUntil", until), BotConversation.class);
    }

    /** Takes one of today's bot messages for this customer. False when the daily cap is used up. */
    public boolean reserveBotMessage(String phone, int cap) {
        return reserve(phone, "botMessages", cap);
    }

    /** Takes one of today's AI choices for this customer. False when the daily cap is used up. */
    public boolean reserveAiCall(String phone, int cap) {
        return reserve(phone, "aiCalls", cap);
    }

    private boolean reserve(String phone, String counter, int cap) {
        String today = LocalDate.now(zone).toString();
        // Same day and still under the cap: count it.
        BotConversation hit = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(phone).and("day").is(today).and(counter).lt(cap)),
                new Update().inc(counter, 1),
                FindAndModifyOptions.options().returnNew(true), BotConversation.class);
        if (hit != null) {
            return true;
        }
        // A new day: start the counters again at one.
        BotConversation fresh = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(phone).and("day").ne(today)),
                new Update().set("day", today).set("botMessages", counter.equals("botMessages") ? 1 : 0).set("aiCalls", counter.equals("aiCalls") ? 1 : 0),
                FindAndModifyOptions.options().returnNew(true), BotConversation.class);
        if (fresh != null) {
            return true;
        }
        // No record yet (a message sent without the customer having written first): create it, counted.
        if (!repository.existsById(phone)) {
            mongo.upsert(Query.query(Criteria.where("_id").is(phone)),
                    new Update().set("day", today).set("botMessages", counter.equals("botMessages") ? 1 : 0).set("aiCalls", counter.equals("aiCalls") ? 1 : 0),
                    BotConversation.class);
            return true;
        }
        return false;
    }
}
