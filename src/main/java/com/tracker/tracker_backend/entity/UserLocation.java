package com.tracker.tracker_backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_locations")
@Getter
@Setter
@NoArgsConstructor
public class UserLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 100)
    private String label;

    @Column(name = "lat_min", nullable = false)
    private double latMin;

    @Column(name = "lat_max", nullable = false)
    private double latMax;

    @Column(name = "lon_min", nullable = false)
    private double lonMin;

    @Column(name = "lon_max", nullable = false)
    private double lonMax;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
