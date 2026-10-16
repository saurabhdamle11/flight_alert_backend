package com.tracker.tracker_backend.service;

import com.tracker.tracker_backend.dto.FlightNotificationMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a flight notification for each delivery channel.
 */
@Component
public class NotificationFormatter {

    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    public String whatsappText(FlightNotificationMessage m) {
        return String.format(
                "Flight %s is overhead!\nAltitude: %s | Speed: %s | Heading: %s\n" +
                "Track on Flightradar24: %s",
                label(m), altitude(m), speed(m), heading(m), trackingUrl(m)
        );
    }

    /**
     * JSON-serialisable payload for the service worker. Keep it small: the encrypted
     * message must fit in a single 4 KB record.
     */
    public Map<String, Object> pushPayload(FlightNotificationMessage m) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "flight");
        p.put("title", "✈ " + label(m) + " is overhead");
        p.put("body", altitude(m) + " · " + speed(m) + " · heading " + heading(m));
        p.put("url", trackingUrl(m));
        p.put("tag", "flight-" + m.icao24());
        p.put("icao24", m.icao24());
        p.put("callsign", callsign(m));
        p.put("altitudeFt", m.altitudeMeters() != null ? Math.round(m.altitudeMeters() * 3.28084) : null);
        p.put("speedKmh", m.speedMs() != null ? Math.round(m.speedMs() * 3.6) : null);
        p.put("heading", m.headingDeg() != null ? toCompass(m.headingDeg()) : null);
        p.put("latitude", m.latitude());
        p.put("longitude", m.longitude());
        p.put("timestamp", Instant.now().toEpochMilli());
        return p;
    }

    public Map<String, Object> testPushPayload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "test");
        p.put("title", "✈ Flight Whisperer is set up");
        p.put("body", "You'll get an alert like this when a plane flies over your area.");
        p.put("url", "/");
        p.put("tag", "test");
        p.put("timestamp", Instant.now().toEpochMilli());
        return p;
    }

    public static String toCompass(double deg) {
        return COMPASS[(int) Math.round(((deg % 360) + 360) % 360 / 45) % 8];
    }

    private static String callsign(FlightNotificationMessage m) {
        return m.callsign() != null && !m.callsign().isBlank() ? m.callsign().trim() : null;
    }

    private static String label(FlightNotificationMessage m) {
        String cs = callsign(m);
        return cs != null ? cs : m.icao24();
    }

    private static String trackingUrl(FlightNotificationMessage m) {
        return "https://www.flightradar24.com/" + label(m);
    }

    private static String altitude(FlightNotificationMessage m) {
        return m.altitudeMeters() != null ? String.format("%,.0f ft", m.altitudeMeters() * 3.28084) : "unknown altitude";
    }

    private static String speed(FlightNotificationMessage m) {
        return m.speedMs() != null ? String.format("%.0f km/h", m.speedMs() * 3.6) : "unknown speed";
    }

    private static String heading(FlightNotificationMessage m) {
        return m.headingDeg() != null ? toCompass(m.headingDeg()) : "unknown direction";
    }
}
