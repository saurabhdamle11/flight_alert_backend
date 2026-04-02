package com.tracker.tracker_backend.service;

import com.tracker.tracker_backend.entity.User;
import com.tracker.tracker_backend.repository.UserLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookConversationService {

    private static final String MSG_WELCOME =
            "Welcome to Flight Whisperer! I'll notify you when planes fly over your area.\n\n" +
            "To get started, please share your location using the WhatsApp attachment button (the paperclip icon) " +
            "and select 'Location'.";

    private static final String MSG_AWAITING_LOCATION =
            "I don't have a location saved for you yet.\n\n" +
            "Please share your location using the WhatsApp attachment button and select 'Location'.";

    private static final String MSG_SUBSCRIBED =
            "You're already subscribed! I'll message you when planes fly over your area.\n\n" +
            "Reply STOP to unsubscribe or LOCATION to update your location.";

    private static final String MSG_LOCATION_SAVED =
            "Got it! Your location has been saved. I'll notify you when planes fly overhead.\n\n" +
            "Reply STOP to unsubscribe at any time.";

    private static final String MSG_STOPPED =
            "You've been unsubscribed. Reply START to re-enable notifications.";

    private static final String MSG_RESTARTED =
            "You're back! I'll resume sending you flight notifications.\n\n" +
            "Reply STOP to unsubscribe at any time.";

    private final UserService userService;
    private final UserLocationRepository userLocationRepository;
    private final WhatsAppService whatsAppService;

    /**
     * Main entry point for every inbound WhatsApp message.
     *
     * @param from      E.164 number as received from Twilio (may include "whatsapp:" prefix)
     * @param body      Text content of the message (trimmed)
     * @param latitude  Non-null when the user shared their WhatsApp location
     * @param longitude Non-null when the user shared their WhatsApp location
     */
    public void handle(String from, String body, Double latitude, Double longitude) {
        // `from` arrives as "whatsapp:+E164" from Twilio; strip prefix for DB storage
        String normalizedNumber = from.startsWith("whatsapp:") ? from.substring(9) : from;
        Optional<User> existing = userService.findByWhatsappNumber(normalizedNumber);

        if (existing.isEmpty()) {
            // First contact — register and ask for location
            userService.registerFromWhatsapp(normalizedNumber);
            log.info("Registered new user: {}", normalizedNumber);
            whatsAppService.sendMessage(from, MSG_WELCOME);
            return;
        }

        User user = existing.get();
        String command = body == null ? "" : body.trim().toUpperCase();

        // Handle STOP keyword
        if ("STOP".equals(command) || "UNSUBSCRIBE".equals(command)) {
            userService.setActive(user.getId(), false);
            whatsAppService.sendMessage(from, MSG_STOPPED);
            return;
        }

        // Handle START / re-subscribe
        if ("START".equals(command) || "SUBSCRIBE".equals(command)) {
            userService.setActive(user.getId(), true);
            whatsAppService.sendMessage(from, MSG_RESTARTED);
            return;
        }

        // If user shared a location pin (Latitude/Longitude params from Twilio)
        if (latitude != null && longitude != null) {
            // Deactivate existing locations before saving the new one
            userLocationRepository.findByUserIdAndActiveTrue(user.getId())
                    .forEach(l -> l.setActive(false));
            userService.addLocationFromPoint(user, latitude, longitude);
            log.info("Saved location ({}, {}) for user {}", latitude, longitude, user.getId());
            whatsAppService.sendMessage(from, MSG_LOCATION_SAVED);
            return;
        }

        // Handle LOCATION keyword — prompt to re-share location
        if ("LOCATION".equals(command) || "UPDATE".equals(command)) {
            whatsAppService.sendMessage(from, MSG_AWAITING_LOCATION);
            return;
        }

        // User exists — check whether they have an active location
        boolean hasLocation = !userLocationRepository.findByUserIdAndActiveTrue(user.getId()).isEmpty();
        if (hasLocation) {
            whatsAppService.sendMessage(from, MSG_SUBSCRIBED);
        } else {
            whatsAppService.sendMessage(from, MSG_AWAITING_LOCATION);
        }
    }
}
