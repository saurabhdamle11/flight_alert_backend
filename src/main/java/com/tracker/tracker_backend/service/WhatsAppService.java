package com.tracker.tracker_backend.service;

import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends outbound WhatsApp messages via the Twilio Messages API.
 * Used by TwilioWebhookController (inbound replies) and, in Phase 3,
 * by NotificationService (Kafka-triggered proactive notifications).
 */
@Service
public class WhatsAppService {

    @Value("${twilio.whatsapp-from}")
    private String fromNumber;

    /**
     * @param toNumber E.164 number of the recipient, e.g. "+919876543210"
     * @param text     Message body to send
     */
    public void sendMessage(String toNumber, String text) {
        String to = toNumber.startsWith("whatsapp:") ? toNumber : "whatsapp:" + toNumber;
        Message.creator(new PhoneNumber(to), new PhoneNumber(fromNumber.strip()), text).create();
    }
}
