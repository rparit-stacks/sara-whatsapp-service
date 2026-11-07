package com.sara.whatsapp.provider;

import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.Template;

/**
 * What the core needs from a WhatsApp provider. Meta Cloud API is the main implementation;
 * another provider can be added without touching the core.
 */
public interface WhatsAppProvider {

    /** Name used in configuration and stored on each message, for example META. */
    String name();

    SendResult send(Message message, Template template);

    HealthStatus health();
}
