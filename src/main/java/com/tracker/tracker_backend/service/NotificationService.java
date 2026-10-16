package com.tracker.tracker_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.tracker_backend.dto.FlightNotificationMessage;
import com.tracker.tracker_backend.entity.Notification;
import com.tracker.tracker_backend.repository.NotificationRepository;
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
import java.util.Map;

/**
 * Consumes {@code flight.notifications} and sends a WhatsApp message to the user
 * via Twilio. Failures are routed to {@code flight.notifications.dlq}.
 *
 * Every successful delivery is written to the {@code notifications} table and
 * published to {@code notification.sent}. Audit failures are logged but never
 * routed to the DLQ, since the user has already been notified.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final WhatsAppService whatsAppService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationRepository notificationRepository;

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

        String messageSid;
        try {
            String text = formatMessage(message);
            messageSid = whatsAppService.sendMessage(message.whatsappNumber(), text);
            log.info("Notified user={} icao24={}", message.userId(), message.icao24());
        } catch (Exception e) {
            log.error("Failed to send WhatsApp notification for user={} icao24={}: {}",
                    message.userId(), message.icao24(), e.getMessage());
            routeToDlq(payload);
            return;
        }

        recordSent(message, messageSid);
    }

    private void recordSent(FlightNotificationMessage m, String messageSid) {
        Instant sentAt = Instant.now();
        try {
            Notification n = new Notification();
            n.setUserId(m.userId());
            n.setIcao24(m.icao24());
            n.setCallsign(m.callsign() != null ? m.callsign().trim() : null);
            n.setAltitudeM(m.altitudeMeters());
            n.setSpeedMs(m.speedMs());
            n.setHeading(m.headingDeg());
            n.setTwilioMessageSid(messageSid);
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
            event.put("messageSid", messageSid);
            event.put("sentAt", sentAt.toString());
            kafkaTemplate.send(notificationSentTopic, m.userId().toString(), objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("Failed to publish notification.sent for user={} icao24={}: {}",
                    m.userId(), m.icao24(), e.getMessage());
        }
    }

    private String formatMessage(FlightNotificationMessage m) {
        String callsign = (m.callsign() != null && !m.callsign().isBlank())
                ? m.callsign().trim() : m.icao24();

        String altitude = m.altitudeMeters() != null
                ? String.format("%.0f ft", m.altitudeMeters() * 3.28084) : "unknown altitude";

        String speed = m.speedMs() != null
                ? String.format("%.0f km/h", m.speedMs() * 3.6) : "unknown speed";

        String heading = m.headingDeg() != null
                ? toCompass(m.headingDeg()) : "unknown direction";

        return String.format(
                "Flight %s is overhead!\nAltitude: %s | Speed: %s | Heading: %s\n" +
                "Track on Flightradar24: https://www.flightradar24.com/%s",
                callsign, altitude, speed, heading, callsign
        );
    }

    private String toCompass(double deg) {
        String[] points = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return points[(int) Math.round(deg / 45) % 8];
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
