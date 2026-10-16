package com.tracker.tracker_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.tracker_backend.client.OpenSkyClient;
import com.tracker.tracker_backend.dto.FlightStateUpdate;
import com.tracker.tracker_backend.dto.OpenSkyResponse;
import com.tracker.tracker_backend.entity.Region;
import com.tracker.tracker_backend.model.BoundingBox;
import com.tracker.tracker_backend.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Polls the OpenSky Network API once per active merged region.
 *
 * Budget-aware scheduling: the scheduler ticks every {@code opensky.min-poll-interval-ms},
 * but a poll cycle only runs when enough time has passed for the cycle's credit cost to
 * fit inside the daily OpenSky budget. With N one-credit regions and a budget of B
 * credits/day, cycles run at most every max(minInterval, N * 86 400 s / B).
 *
 * For each region:
 *   - Skip if the region's local time is in the overnight window (default 2–5 AM)
 *   - Call OpenSky, discard on-ground flights
 *   - Publish one FlightStateUpdate per airborne flight to flight.state.updates
 *
 * The partition key is the region ID so flights in the same region always go
 * to the same Kafka partition, preserving per-region ordering.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlightIngestionService {

    private final RegionRepository regionRepository;
    private final OpenSkyClient openSkyClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${flight.kafka.topic.state-updates}")
    private String stateUpdatesTopic;

    @Value("${opensky.overnight-start-hour:2}")
    private int overnightStart;

    @Value("${opensky.overnight-end-hour:5}")
    private int overnightEnd;

    @Value("${opensky.min-poll-interval-ms:15000}")
    private long minPollIntervalMs;

    // 0 = auto: 90% of the standard (4 000) or anonymous (400) daily quota
    @Value("${opensky.daily-credit-budget:0}")
    private int dailyCreditBudget;

    private Instant lastCycleAt = Instant.EPOCH;

    @Scheduled(fixedDelayString = "${opensky.min-poll-interval-ms:15000}")
    public void poll() {
        List<Region> regions = regionRepository.findAll().stream()
                .filter(r -> !isOvernightForRegion(r))
                .toList();

        if (regions.isEmpty()) {
            log.debug("No active (non-overnight) regions to poll");
            return;
        }

        int cycleCost = regions.stream().mapToInt(r -> OpenSkyClient.creditCost(toBox(r))).sum();
        Duration interval = cycleInterval(cycleCost);
        if (Instant.now().isBefore(lastCycleAt.plus(interval))) {
            return;
        }
        lastCycleAt = Instant.now();

        log.info("Polling {} region(s) — cycle cost {} credit(s), interval {}s",
                regions.size(), cycleCost, interval.toSeconds());

        for (Region region : regions) {
            BoundingBox box = toBox(region);

            OpenSkyResponse response = openSkyClient.fetchStates(box);
            if (response == null || response.states() == null) {
                continue;
            }

            int published = 0;
            for (List<Object> state : response.states()) {
                if (state == null || state.size() < 11) continue;

                Boolean onGround = response.onGround(state);
                if (Boolean.TRUE.equals(onGround)) continue;

                Double lat = response.latitude(state);
                Double lon = response.longitude(state);
                if (lat == null || lon == null) continue;

                FlightStateUpdate update = new FlightStateUpdate(
                        response.icao24(state),
                        response.callsign(state),
                        lat,
                        lon,
                        response.altitude(state),
                        response.speed(state),
                        response.heading(state),
                        onGround,
                        region.getId().toString()
                );

                try {
                    String payload = objectMapper.writeValueAsString(update);
                    kafkaTemplate.send(stateUpdatesTopic, update.regionKey(), payload);
                    published++;
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize FlightStateUpdate for icao24={}: {}", update.icao24(), e.getMessage());
                }
            }

            log.info("Region {} → {} airborne flights published", region.getId(), published);
        }
    }

    private Duration cycleInterval(int cycleCost) {
        int budget = dailyCreditBudget > 0
                ? dailyCreditBudget
                : (openSkyClient.isAuthenticated() ? 3_600 : 360);
        long budgetMs = (long) Math.ceil(cycleCost * 86_400_000.0 / budget);
        return Duration.ofMillis(Math.max(minPollIntervalMs, budgetMs));
    }

    private static BoundingBox toBox(Region region) {
        return new BoundingBox(region.getLatMin(), region.getLatMax(), region.getLonMin(), region.getLonMax());
    }

    /**
     * Rough overnight check: estimates local hour from the region's center longitude.
     * UTC offset ≈ longitude / 15. Good enough for suppressing unnecessary polls.
     */
    private boolean isOvernightForRegion(Region region) {
        double centerLon = (region.getLonMin() + region.getLonMax()) / 2.0;
        int utcOffsetHours = (int) Math.round(centerLon / 15.0);
        int localHour = ZonedDateTime.now(ZoneOffset.ofHours(
                Math.max(-12, Math.min(14, utcOffsetHours))
        )).getHour();
        return localHour >= overnightStart && localHour < overnightEnd;
    }
}
