package com.tracker.tracker_backend.service;

import com.uber.h3core.H3Core;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Thin wrapper around H3Core providing cell lookup and neighbor expansion.
 *
 * Resolution 5 cells have an edge length of ~8.5 km. kRing(3) expands outward
 * 3 rings (~25.5 km), covering the default 25 km user watch radius.
 */
@Slf4j
@Service
public class H3IndexService {

    private final H3Core h3;

    @Value("${h3.resolution:5}")
    private int resolution;

    @Value("${h3.kring-radius:3}")
    private int kRingRadius;

    public H3IndexService() {
        try {
            this.h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialise H3Core native library", e);
        }
    }

    /** Returns the H3 cell ID for a given lat/lon at the configured resolution. */
    public long cellForPoint(double lat, double lon) {
        return h3.latLngToCell(lat, lon, resolution);
    }

    /**
     * Returns all H3 cells within kRingRadius rings of the cell containing (lat, lon).
     * Used by SubscriptionMatcherService to build the candidate set of user locations.
     */
    public List<Long> neighborCells(double lat, double lon) {
        long cell = h3.latLngToCell(lat, lon, resolution);
        return h3.gridDisk(cell, kRingRadius);
    }
}
