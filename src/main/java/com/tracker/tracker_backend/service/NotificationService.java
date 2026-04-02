package com.tracker.tracker_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.tracker_backend.dto.FlightNotificationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

/**
 * Consumes {@code flight.notifications} and sends a WhatsApp message to the user
 * via Twilio. Failures are routed to {@code flight.notifications.dlq}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final WhatsAppService whatsAppService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${flight.kafka.topic.notifications-dlq}")
    private String dlqTopic;

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

        try {
            String text = formatMessage(message);
            whatsAppService.sendMessage(message.whatsappNumber(), text);
            log.info("Notified user={} icao24={}", message.userId(), message.icao24());
        } catch (Exception e) {
            log.error("Failed to send WhatsApp notification for user={} icao24={}: {}",
                    message.userId(), message.icao24(), e.getMessage());
            routeToDlq(payload);
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
