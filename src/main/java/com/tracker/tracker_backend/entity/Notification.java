package com.tracker.tracker_backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit row for every notification successfully delivered to a user.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 10)
    private String icao24;

    @Column(length = 20)
    private String callsign;

    @Column(name = "origin_country", length = 100)
    private String originCountry;

    @Column(name = "altitude_m")
    private Double altitudeM;

    @Column(name = "speed_ms")
    private Double speedMs;

    private Double heading;

    @Column(name = "twilio_message_sid", length = 50)
    private String twilioMessageSid;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();
}
