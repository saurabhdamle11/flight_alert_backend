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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Polls the OpenSky Network API on a fixed schedule, once per active merged region.
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

    @Scheduled(fixedDelayString = "${opensky.poll-interval-ms:10000}")
    public void poll() {
        List<Region> regions = regionRepository.findAll();

        if (regions.isEmpty()) {
            log.debug("No active regions to poll");
            return;
        }

        log.info("Polling {} region(s)", regions.size());

        for (Region region : regions) {
            if (isOvernightForRegion(region)) {
                log.debug("Skipping overnight region {}", region.getId());
                continue;
            }

            BoundingBox box = new BoundingBox(
                    region.getLatMin(), region.getLatMax(),
                    region.getLonMin(), region.getLonMax()
            );

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
