package com.sara.whatsapp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {

    /** Active provider when nothing has been chosen through the API (META or DOUBLETICK). */
    private String provider = "META";
    private String defaultCountryCode = "91";
    /** Base64 AES key (16/24/32 bytes) used for secrets stored in the database. */
    private String encryptionKey = "";
    /** Protects /v1/admin/**. */
    private String adminApiKey = "";
    /** A reply that is exactly one of these (any case) opts the sender out. */
    private java.util.List<String> optOutKeywords = java.util.List.of("STOP", "STOPALL", "UNSUBSCRIBE", "OPTOUT");

    private Meta meta = new Meta();
    private Send send = new Send();
    private Events events = new Events();
    private Broadcast broadcast = new Broadcast();
    private Usage usage = new Usage();
    private AutoReply autoReply = new AutoReply();
    private Bot bot = new Bot();

    @Getter
    @Setter
    public static class Meta {
        private String baseUrl = "https://graph.facebook.com";
        private String graphVersion = "v24.0";
        private String wabaId = "";
        private String phoneNumberId = "";
        private String accessToken = "";
        private String appSecret = "";
        private String appId = "";
        private String webhookVerifyToken = "";
        /** Opened by the "billing link" endpoint. Set it to your WhatsApp billing page. */
        private String billingUrl = "https://business.facebook.com/billing_hub/";
    }

    @Getter
    @Setter
    public static class Send {
        private int concurrency = 8;
        private int leaseSeconds = 60;
        private int maxAttempts = 5;
        private int baseBackoffSeconds = 10;
        private int maxBackoffSeconds = 1800;
    }

    @Getter
    @Setter
    public static class Broadcast {
        /**
         * Unique recipients a broadcast may start within a rolling 24 hours. Set it to your Meta
         * messaging limit (a new account starts at 250). Broadcasts wait for room, they do not fail.
         */
        private int dailyLimit = 250;
        private int chunkSize = 200;
    }

    @Getter
    @Setter
    public static class AutoReply {
        /** Template (our key) sent to someone who writes to the number. Blank turns the auto-reply off. */
        private String templateKey = "";
        /** One auto-reply per sender in this many hours, however many messages they send. */
        private int cooldownHours = 6;
    }

    @Getter
    @Setter
    public static class Bot {
        /** Master switch in this service. The Marketplace licence must also be live (see licenceRequired). */
        private boolean enabled = true;
        /** The backend that knows whether the bot's trial or purchase is valid. */
        private String backendUrl = "https://studiosara.cloud";
        /** Marketplace product key of the bot. */
        private String productKey = "bot-whatsapp";
        /** When false the licence check is skipped (local testing only). */
        private boolean licenceRequired = true;
        /** How long the last good licence answer is trusted if the backend cannot be reached. */
        private int licenceGraceMinutes = 30;
        /** Bot messages one customer can receive per day. */
        private int dailyMessageCap = 40;
        /** AI choices per customer per day. */
        private int dailyAiCap = 20;
        /** An AI choice below this confidence falls back to the safe app link. */
        private double minConfidence = 0.55;
        /** Where the "open the app" buttons go. */
        private String deepLinkBase = "https://open.studiosara.in";
        /** Sent to the backend in X-Bot-Secret. Must match the backend's bot.shared-secret. Blank = the bot cannot use AI, order lookup or team alerts. */
        private String sharedSecret = "";
        /** "Within business hours" conditions use these, in the usage zone (Asia/Kolkata). */
        private String businessStart = "10:00";
        private String businessEnd = "19:00";
        private String businessDays = "MON,TUE,WED,THU,FRI,SAT";
        /** The most messages the bot sends in answer to one incoming message. */
        private int maxMessagesPerInbound = 4;
    }

    @Getter
    @Setter
    public static class Usage {
        /** Month boundaries for the usage report. */
        private String zone = "Asia/Kolkata";
        /** GST added to Meta's rates in the estimate. Meta's rate card is quoted before tax. */
        private double gstPercent = 18;
    }

    @Getter
    @Setter
    public static class Events {
        private int maxAttempts = 8;
        private int baseBackoffSeconds = 10;
        private int maxBackoffSeconds = 3600;
    }
}
