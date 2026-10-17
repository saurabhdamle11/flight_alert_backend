package com.tracker.tracker_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.tracker_backend.dto.FlightNotificationMessage;
import com.tracker.tracker_backend.entity.Notification;
import com.tracker.tracker_backend.entity.NotificationChannel;
import com.tracker.tracker_backend.entity.PushSubscription;
import com.tracker.tracker_backend.repository.NotificationRepository;
import com.tracker.tracker_backend.repository.PushSubscriptionRepository;
import com.tracker.tracker_backend.webpush.WebPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes {@code flight.notifications} and delivers each alert on every channel the
 * user has: WhatsApp (if they have a number and Twilio is configured) and web push
 * (one message per registered browser).
 *
 * Push subscriptions the push service reports as gone (404/410) are deleted.
 * The message is routed to {@code flight.notifications.dlq} only when nothing was
 * delivered and at least one channel failed transiently (429/5xx/network; permanent 4xx
 * rejections are logged, not retried) — partial success is not
 * retried, so users never receive duplicates.
 *
 * Every successful delivery is written to the {@code notifications} table (one row
 * per channel) and published to {@code notification.sent}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final WhatsAppService whatsAppService;
    private final WebPushService webPushService;
    private final NotificationFormatter formatter;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final NotificationRepository notificationRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${flight.kafka.topic.notifications-dlq}")
    private String dlqTopic;

    @Value("${flight.kafka.topic.notification-sent}")
    private String notificationSentTopic;

    @KafkaListener(
            topics = "${flight.kafka.topic.notifications}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onNotification(
            String payload,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        FlightNotificationMessage message;
        try {
            message = objectMapper.readValue(payload, FlightNotificationMessage.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize FlightNotificationMessage at partition={} offset={}: {}",
                    partition, offset, e.getMessage());
            return;
        }

        int delivered = 0;
        int transientFailures = 0;

        if (message.whatsappNumber() != null && whatsAppService.isEnabled()) {
            try {
                String sid = whatsAppService.sendMessage(message.whatsappNumber(), formatter.whatsappText(message));
                recordSent(message, NotificationChannel.WHATSAPP, sid);
                delivered++;
            } catch (Exception e) {
                log.error("WhatsApp delivery failed for user={} icao24={}: {}",
                        message.userId(), message.icao24(), e.getMessage());
                transientFailures++;
            }
        }

        PushDelivery push = deliverPush(message.userId(), formatter.pushPayload(message));
        if (push.sent() > 0) {
            recordSent(message, NotificationChannel.WEB_PUSH, push.firstMessageId());
            delivered++;
        }
        transientFailures += push.failed();

        if (delivered > 0) {
            log.info("Notified user={} icao24={} (push devices={})", message.userId(), message.icao24(), push.sent());
        } else if (transientFailures > 0) {
            routeToDlq(payload);
        } else {
            log.debug("No delivery channel for user={} icao24={}", message.userId(), message.icao24());
        }
    }

    /** Sends a test notification to all of the user's browsers. */
    public PushDelivery sendTestPush(UUID userId) {
        return deliverPush(userId, formatter.testPushPayload());
    }

    public record PushDelivery(int sent, int failed, int removed, String firstMessageId) {}

    private PushDelivery deliverPush(UUID userId, Map<String, Object> payload) {
        if (!webPushService.isEnabled()) {
            return new PushDelivery(0, 0, 0, null);
        }
        List<PushSubscription> subscriptions = pushSubscriptionRepository.findByUserId(userId);
        if (subscriptions.isEmpty()) {
            return new PushDelivery(0, 0, 0, null);
        }

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize push payload", e);
        }

        int sent = 0, failed = 0, removed = 0;
        String firstMessageId = null;
        for (PushSubscription sub : subscriptions) {
            WebPushService.SendResult result = webPushService.send(sub, body);
            switch (result.outcome()) {
                case SENT -> {
                    sent++;
                    if (firstMessageId == null) firstMessageId = result.messageId();
                    sub.setLastSuccessAt(Instant.now());
                    pushSubscriptionRepository.save(sub);
                }
                case GONE -> {
                    removed++;
                    pushSubscriptionRepository.delete(sub);
                    log.info("Removed expired push subscription {} for user={} (HTTP {})",
                            sub.getId(), userId, result.status());
                }
                case REJECTED -> log.error("Push to subscription {} for user={} permanently rejected (HTTP {})",
                        sub.getId(), userId, result.status());
                case FAILED -> failed++;
            }
        }
        return new PushDelivery(sent, failed, removed, firstMessageId);
    }

    private void recordSent(FlightNotificationMessage m, NotificationChannel channel, String providerMessageId) {
        Instant sentAt = Instant.now();
        try {
            Notification n = new Notification();
            n.setUserId(m.userId());
            n.setIcao24(m.icao24());
            n.setCallsign(m.callsign() != null ? m.callsign().trim() : null);
            n.setAltitudeM(m.altitudeMeters());
            n.setSpeedMs(m.speedMs());
            n.setHeading(m.headingDeg());
            n.setChannel(channel);
            n.setProviderMessageId(providerMessageId != null && providerMessageId.length() > 255
                    ? providerMessageId.substring(0, 255) : providerMessageId);
            n.setSentAt(sentAt);
            notificationRepository.save(n);
        } catch (Exception e) {
            log.error("Failed to persist notification audit for user={} icao24={}: {}",
                    m.userId(), m.icao24(), e.getMessage());
        }

        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("userId", m.userId());
            event.put("icao24", m.icao24());
            event.put("callsign", m.callsign());
            event.put("channel", channel.name());
            event.put("providerMessageId", providerMessageId);
            event.put("sentAt", sentAt.toString());
            kafkaTemplate.send(notificationSentTopic, m.userId().toString(), objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("Failed to publish notification.sent for user={} icao24={}: {}",
                    m.userId(), m.icao24(), e.getMessage());
        }
    }

    private void routeToDlq(String originalPayload) {
        kafkaTemplate.send(dlqTopic, originalPayload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to route message to DLQ: {}", ex.getMessage());
                    } else {
                        log.warn("Message routed to DLQ");
                    }
                });
    }
}
