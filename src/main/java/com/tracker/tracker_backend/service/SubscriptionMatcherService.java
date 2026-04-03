package com.tracker.tracker_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.tracker_backend.dto.FlightNotificationMessage;
import com.tracker.tracker_backend.dto.FlightStateUpdate;
import com.tracker.tracker_backend.entity.UserLocation;
import com.tracker.tracker_backend.repository.UserLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Consumes {@code flight.state.updates}, matches each flight against all active
 * user bounding boxes, deduplicates via Redis, and emits one
 * {@code FlightNotificationMessage} per matched user to {@code flight.notifications}.
 *
 * Parallelism: one consumer thread per partition (concurrency=4 in KafkaConfig).
 * Fan-out (1 flight → N users) happens here, not in Kafka partitioning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionMatcherService {

    // Redis TTL for dedup: 10 minutes
    private static final Duration DEDUP_TTL = Duration.ofMinutes(10);
    private static final String DEDUP_KEY_PREFIX = "seen:";

    private final UserLocationRepository userLocationRepository;
    private final H3IndexService h3IndexService;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${flight.kafka.topic.notifications}")
    private String notificationsTopic;

    @KafkaListener(
            topics = "${flight.kafka.topic.state-updates}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onFlightUpdate(
            String payload,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        FlightStateUpdate update;
        try {
            update = objectMapper.readValue(payload, FlightStateUpdate.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize FlightStateUpdate at partition={} offset={}: {}",
                    partition, offset, e.getMessage());
            return;
        }

        log.info("Received flight update: icao24={} lat={} lon={} onGround={}",
                update.icao24(), update.latitude(), update.longitude(), update.onGround());

        // Discard flights confirmed on the ground; keep null (unknown)
        if (Boolean.TRUE.equals(update.onGround())) {
            log.info("Discarding on-ground flight: icao24={}", update.icao24());
            return;
        }

        // Coarse filter: fetch only user locations whose H3 cell is within kRing of the flight.
        // Fine filter: confirm the flight's exact lat/lon falls inside the user's bounding box.
        List<Long> cells = h3IndexService.neighborCells(update.latitude(), update.longitude());
        List<UserLocation> matches = userLocationRepository.findActiveLocationsInCells(cells)
                .stream()
                .filter(ul -> update.latitude()  >= ul.getLatMin() && update.latitude()  <= ul.getLatMax()
                           && update.longitude() >= ul.getLonMin() && update.longitude() <= ul.getLonMax())
                .toList();

        log.info("Flight {} matched {} user location(s)", update.icao24(), matches.size());

        if (matches.isEmpty()) {
            return;
        }

        for (UserLocation loc : matches) {
            String userId = loc.getUser().getId().toString();
            String dedupKey = DEDUP_KEY_PREFIX + userId + ":" + update.icao24();

            // setIfAbsent returns true only on the first insert within the TTL window
            Boolean isNew = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL);
            if (!Boolean.TRUE.equals(isNew)) {
                log.debug("Dedup skip: user={} icao24={}", userId, update.icao24());
                continue;
            }

            FlightNotificationMessage message = new FlightNotificationMessage(
                    loc.getUser().getId(),
                    loc.getUser().getWhatsappNumber(),
                    update.icao24(),
                    update.callsign(),
                    update.latitude(),
                    update.longitude(),
                    update.altitudeMeters(),
                    update.speedMs(),
                    update.headingDeg()
            );

            try {
                String json = objectMapper.writeValueAsString(message);
                // Partition key: userId so all notifications for the same user land on one partition
                kafkaTemplate.send(notificationsTopic, userId, json)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to publish notification for user={} icao24={}: {}",
                                        userId, update.icao24(), ex.getMessage());
                            }
                        });
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize FlightNotificationMessage for user={}: {}", userId, e.getMessage());
            }
        }
    }
}
