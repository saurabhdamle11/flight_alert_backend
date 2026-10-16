package com.tracker.tracker_backend.config;

import com.twilio.Twilio;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class TwilioConfig {

    @Value("${twilio.account-sid:}")
    private String accountSid;

    @Value("${twilio.auth-token:}")
    private String authToken;

    @PostConstruct
    public void init() {
        if (accountSid.isBlank() || authToken.isBlank()) {
            log.warn("Twilio credentials not configured — WhatsApp notifications are disabled");
            return;
        }
        Twilio.init(accountSid.strip(), authToken.strip());
    }
}
