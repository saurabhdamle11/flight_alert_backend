package com.tracker.tracker_backend.model;

/**
 * Immutable geographic bounding box.
 *
 * Used by BoundingBoxMerger to compute merged regions for OpenSky API calls.
 * Not a JPA entity — see Region for the persisted form.
 */
public record BoundingBox(double latMin, double latMax, double lonMin, double lonMax) {

    /**
     * True if this box overlaps or touches the other box.
     */
    public boolean overlaps(BoundingBox other) {
        return this.latMin <= other.latMax
                && this.latMax >= other.latMin
                && this.lonMin <= other.lonMax
                && this.lonMax >= other.lonMin;
    }

    /**
     * Returns the smallest box that contains both this and the other box.
     */
    public BoundingBox merge(BoundingBox other) {
        return new BoundingBox(
                Math.min(this.latMin, other.latMin),
                Math.max(this.latMax, other.latMax),
                Math.min(this.lonMin, other.lonMin),
                Math.max(this.lonMax, other.lonMax)
        );
    }

    public double latSpan() {
        return latMax - latMin;
    }

    public double lonSpan() {
        return lonMax - lonMin;
    }
}
