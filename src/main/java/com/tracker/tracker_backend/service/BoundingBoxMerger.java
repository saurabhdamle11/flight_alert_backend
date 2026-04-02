package com.tracker.tracker_backend.service;

import com.tracker.tracker_backend.model.BoundingBox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Merges a set of bounding boxes into a minimal list of non-overlapping boxes
 * using a greedy sweep sorted by latMin.
 *
 * A merge is skipped when the resulting box would exceed the configured size cap
 * (~200 km x 200 km by default) to avoid pulling in far-away aircraft.
 */
@Component
public class BoundingBoxMerger {

    @Value("${opensky.max-region-degrees-lat:1.8}")
    private double maxLatDegrees;

    @Value("${opensky.max-region-degrees-lon:2.5}")
    private double maxLonDegrees;

    public List<BoundingBox> merge(List<BoundingBox> boxes) {
        if (boxes.isEmpty()) {
            return List.of();
        }

        // Sort by latMin so the sweep has a consistent pass order
        List<BoundingBox> sorted = boxes.stream()
                .sorted(Comparator.comparingDouble(BoundingBox::latMin))
                .toList();

        List<BoundingBox> merged = new ArrayList<>();
        BoundingBox current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            BoundingBox next = sorted.get(i);
            if (current.overlaps(next)) {
                BoundingBox candidate = current.merge(next);
                if (candidate.latSpan() <= maxLatDegrees && candidate.lonSpan() <= maxLonDegrees) {
                    current = candidate;
                    continue;
                }
            }
            merged.add(current);
            current = next;
        }
        merged.add(current);

        return merged;
    }
}
