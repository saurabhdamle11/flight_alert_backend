package com.tracker.tracker_backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Jackson DTO for the OpenSky Network /states/all response.
 *
 * Each element of {@code states} is a heterogeneous array:
 *   [0]  icao24          String
 *   [1]  callsign        String (nullable)
 *   [2]  origin_country  String
 *   [3]  time_position   Long (nullable)
 *   [4]  last_contact    Long
 *   [5]  longitude       Double (nullable)
 *   [6]  latitude        Double (nullable)
 *   [7]  baro_altitude   Double (nullable)
 *   [8]  on_ground       Boolean
 *   [9]  velocity        Double m/s (nullable)
 *   [10] true_track      Double degrees (nullable)
 *   [11] vertical_rate   Double (nullable)
 *   ...  (remaining fields ignored)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenSkyResponse(long time, List<List<Object>> states) {

    public String icao24(List<Object> state)     { return (String)  state.get(0); }
    public String callsign(List<Object> state)   { return (String)  state.get(1); }
    public Double longitude(List<Object> state)  { return toDouble(state.get(5)); }
    public Double latitude(List<Object> state)   { return toDouble(state.get(6)); }
    public Double altitude(List<Object> state)   { return toDouble(state.get(7)); }
    public Boolean onGround(List<Object> state)  { return (Boolean) state.get(8); }
    public Double speed(List<Object> state)      { return toDouble(state.get(9)); }
    public Double heading(List<Object> state)    { return toDouble(state.get(10)); }

    private static Double toDouble(Object val) {
        if (val == null) return null;
        if (val instanceof Double d) return d;
        if (val instanceof Number n) return n.doubleValue();
        return null;
    }
}
