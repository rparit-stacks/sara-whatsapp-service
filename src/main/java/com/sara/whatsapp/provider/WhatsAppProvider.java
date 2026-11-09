package com.sara.whatsapp.provider;

import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.Template;

import java.util.Map;

/**
 * What the core needs from a WhatsApp provider. Meta Cloud API is the main implementation;
 * another provider can be added without touching the core.
 */
public interface WhatsAppProvider {

    /** Name used in configuration and stored on each message, for example META. */
    String name();

    SendResult send(Message message, Template template);

    /**
     * Sends a free-form message (text or interactive). WhatsApp allows these only inside the 24-hour window that
     * opens when the customer writes; the caller checks the window. {@code message} holds the type-specific part
     * of the payload, for example {@code type} and {@code text} or {@code interactive}.
     */
    default SendResult sendSession(String to, Map<String, Object> message, String callbackId) {
        return SendResult.permanent("UNSUPPORTED", "This provider cannot send free-form messages");
    }

    HealthStatus health();
}
