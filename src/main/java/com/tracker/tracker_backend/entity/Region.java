package com.tracker.tracker_backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "regions")
@Getter
@Setter
@NoArgsConstructor
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "lat_min", nullable = false)
    private double latMin;

    @Column(name = "lat_max", nullable = false)
    private double latMax;

    @Column(name = "lon_min", nullable = false)
    private double lonMin;

    @Column(name = "lon_max", nullable = false)
    private double lonMax;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Region(double latMin, double latMax, double lonMin, double lonMax) {
        this.latMin = latMin;
        this.latMax = latMax;
        this.lonMin = lonMin;
        this.lonMax = lonMax;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
