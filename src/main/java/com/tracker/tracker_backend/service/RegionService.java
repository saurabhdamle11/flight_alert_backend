package com.tracker.tracker_backend.service;

import com.tracker.tracker_backend.entity.Region;
import com.tracker.tracker_backend.entity.UserLocation;
import com.tracker.tracker_backend.model.BoundingBox;
import com.tracker.tracker_backend.repository.RegionRepository;
import com.tracker.tracker_backend.repository.UserLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reloads active user locations from the DB every 5 minutes, merges their
 * bounding boxes into a minimal set, and persists them to the {@code regions}
 * table. The {@code FlightIngestionService} reads from {@code regions} to
 * decide which areas to poll on OpenSky.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionService {

    private final UserLocationRepository userLocationRepository;
    private final RegionRepository regionRepository;
    private final BoundingBoxMerger merger;

    @Scheduled(fixedDelayString = "${opensky.region-reload-interval-ms:300000}",
               initialDelay = 0)
    @Transactional
    public void reloadRegions() {
        List<UserLocation> locations = userLocationRepository.findAllActiveWithUser();

        if (locations.isEmpty()) {
            regionRepository.deleteAll();
            log.info("No active user locations — cleared all regions");
            return;
        }

        List<BoundingBox> boxes = locations.stream()
                .map(ul -> new BoundingBox(ul.getLatMin(), ul.getLatMax(), ul.getLonMin(), ul.getLonMax()))
                .toList();

        List<BoundingBox> merged = merger.merge(boxes);

        // Replace all existing regions with the freshly merged set.
        // ON DELETE SET NULL on user_locations.region_id means no orphan FK issues.
        regionRepository.deleteAll();

        List<Region> newRegions = merged.stream()
                .map(bb -> new Region(bb.latMin(), bb.latMax(), bb.lonMin(), bb.lonMax()))
                .toList();
        regionRepository.saveAll(newRegions);

        // Assign each user location to the merged region that contains its center point
        for (UserLocation ul : locations) {
            double centerLat = (ul.getLatMin() + ul.getLatMax()) / 2.0;
            double centerLon = (ul.getLonMin() + ul.getLonMax()) / 2.0;

            newRegions.stream()
                    .filter(r -> r.getLatMin() <= centerLat && r.getLatMax() >= centerLat
                              && r.getLonMin() <= centerLon && r.getLonMax() >= centerLon)
                    .findFirst()
                    .ifPresent(r -> ul.setRegionId(r.getId()));
        }

        log.info("Region reload: {} user locations → {} merged region(s)", locations.size(), newRegions.size());
    }
}
