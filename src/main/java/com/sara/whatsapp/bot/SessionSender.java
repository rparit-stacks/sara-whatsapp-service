package com.sara.whatsapp.bot;

import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ConsentStatus;
import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageStatus;
import com.sara.whatsapp.provider.ProviderRegistry;
import com.sara.whatsapp.provider.SendResult;
import com.sara.whatsapp.provider.WhatsAppProvider;
import com.sara.whatsapp.service.ContactService;
import com.sara.whatsapp.util.Phone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sends the bot's free-form replies. Before anything leaves it checks the 24-hour window, that the customer has not
 * opted out, that a person has not taken over the chat, and the customer's daily cap. Every message sent is logged in
 * the same collection as template messages, as category SERVICE, so cost and delivery show up in usage like any other.
 */
@Service
public class SessionSender {

    private static final Logger log = LoggerFactory.getLogger(SessionSender.class);
    public static final String CLIENT_ID = "bot";

    public record Result(boolean sent, String reason, String messageId) {
        static Result blocked(String reason) {
            return new Result(false, reason, null);
        }
    }

    private final ProviderRegistry providers;
    private final BotConversationService conversations;
    private final ContactService contacts;
    private final MongoTemplate mongo;
    private final WhatsAppProperties props;

    public SessionSender(ProviderRegistry providers, BotConversationService conversations, ContactService contacts,
                         MongoTemplate mongo, WhatsAppProperties props) {
        this.providers = providers;
        this.conversations = conversations;
        this.contacts = contacts;
        this.mongo = mongo;
        this.props = props;
    }

    /** @param outcome the id of the outcome that produced this message, kept in the log for reporting. */
    public Result send(String phone, Map<String, Object> payload, String outcome) {
        Instant now = Instant.now();
        if (!conversations.windowOpen(phone, now)) {
            return Result.blocked("WINDOW_CLOSED");
        }
        if (contacts.consentOf(phone) == ConsentStatus.OPTED_OUT) {
            return Result.blocked("OPTED_OUT");
        }
        if (conversations.botPaused(phone, now)) {
            return Result.blocked("HUMAN_HAS_CHAT");
        }
        if (!conversations.reserveBotMessage(phone, props.getBot().getDailyMessageCap())) {
            return Result.blocked("DAILY_CAP");
        }

        String id = UUID.randomUUID().toString();
        WhatsAppProvider provider = providers.active();
        SendResult result = provider.sendSession(phone, payload, id);
        if (!result.success()) {
            log.warn("Bot message to {} failed: {} {}", phone, result.errorCode(), result.errorMessage());
            return Result.blocked("SEND_FAILED:" + result.errorCode());
        }

        Message m = new Message();
        m.setId(id);
        m.setClientId(CLIENT_ID);
        m.setClientMessageId("bot-" + id);
        m.setTemplateKey("bot:" + outcome);
        m.setCategory("SERVICE");
        m.setTo(phone);
        m.setRecipientCountry(Phone.countryCode(phone));
        m.setProvider(provider.name());
        m.setProviderMessageId(result.providerMessageId());
        m.setStatus(MessageStatus.SENT);
        m.setAttempts(1);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        m.setSentAt(now);
        mongo.insert(m);
        return new Result(true, null, id);
    }
}
