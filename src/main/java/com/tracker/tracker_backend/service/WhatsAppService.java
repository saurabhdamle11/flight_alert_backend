package com.tracker.tracker_backend.service;

import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends outbound WhatsApp messages via the Twilio Messages API.
 * Disabled when Twilio is not configured (see TwilioConfig).
 */
@Service
public class WhatsAppService {

    @Value("${twilio.account-sid:}")
    private String accountSid;

    @Value("${twilio.auth-token:}")
    private String authToken;

    @Value("${twilio.whatsapp-from:}")
    private String fromNumber;

    public boolean isEnabled() {
        return !accountSid.isBlank() && !authToken.isBlank() && !fromNumber.isBlank();
    }

    /**
     * @param toNumber E.164 number of the recipient, e.g. "+919876543210"
     * @param text     Message body to send
     * @return Twilio message SID
     */
    public String sendMessage(String toNumber, String text) {
        if (!isEnabled()) {
            throw new IllegalStateException("WhatsApp (Twilio) is not configured");
        }
        String to = toNumber.startsWith("whatsapp:") ? toNumber : "whatsapp:" + toNumber;
        return Message.creator(new PhoneNumber(to), new PhoneNumber(fromNumber.strip()), text).create().getSid();
    }
}
