package com.tracker.tracker_backend.service;

import com.tracker.tracker_backend.dto.CreateUserRequest;
import com.tracker.tracker_backend.dto.MeResponse;
import com.tracker.tracker_backend.dto.NotificationView;
import com.tracker.tracker_backend.dto.WebRegisterRequest;
import com.tracker.tracker_backend.dto.WebRegisterResponse;
import com.tracker.tracker_backend.dto.UpdateLocationRequest;
import com.tracker.tracker_backend.dto.UserResponse;
import com.tracker.tracker_backend.entity.User;
import com.tracker.tracker_backend.entity.UserLocation;
import com.tracker.tracker_backend.exception.DuplicateWhatsappNumberException;
import com.tracker.tracker_backend.exception.UserNotFoundException;
import com.tracker.tracker_backend.repository.NotificationRepository;
import com.tracker.tracker_backend.repository.PushSubscriptionRepository;
import com.tracker.tracker_backend.repository.UserLocationRepository;
import com.tracker.tracker_backend.repository.UserRepository;
import com.tracker.tracker_backend.security.ApiTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserLocationRepository userLocationRepository;
    private final H3IndexService h3IndexService;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final NotificationRepository notificationRepository;

    private static final double DEFAULT_RADIUS_KM = 25.0;

    @Transactional
    public UserResponse createUser(CreateUserRequest req) {
        if (userRepository.existsByWhatsappNumber(req.whatsappNumber())) {
            throw new DuplicateWhatsappNumberException(req.whatsappNumber());
        }

        User user = new User();
        user.setWhatsappNumber(req.whatsappNumber());
        user.setDisplayName(req.displayName());

        if (req.location() != null) {
            validateBoundingBox(req.location().latMin(), req.location().latMax(),
                    req.location().lonMin(), req.location().lonMax());

            UserLocation loc = new UserLocation();
            loc.setUser(user);
            loc.setLabel(req.location().label());
            loc.setLatMin(req.location().latMin());
            loc.setLatMax(req.location().latMax());
            loc.setLonMin(req.location().lonMin());
            loc.setLonMax(req.location().lonMax());
            double centerLat = (req.location().latMin() + req.location().latMax()) / 2.0;
            double centerLon = (req.location().lonMin() + req.location().lonMax()) / 2.0;
            loc.setH3Index(h3IndexService.cellForPoint(centerLat, centerLon));
            user.getLocations().add(loc);
        }

        return toResponse(userRepository.save(user));
    }

    @Transactional
    public void updateLocation(UUID userId, UpdateLocationRequest req) {
        User user = findActive(userId);
        validateBoundingBox(req.latMin(), req.latMax(), req.lonMin(), req.lonMax());

        // Deactivate all existing locations for this user, then add the new one.
        userLocationRepository.findByUserIdAndActiveTrue(userId)
                .forEach(l -> l.setActive(false));

        UserLocation loc = new UserLocation();
        loc.setUser(user);
        loc.setLabel(req.label());
        loc.setLatMin(req.latMin());
        loc.setLatMax(req.latMax());
        loc.setLonMin(req.lonMin());
        loc.setLonMax(req.lonMax());
        double centerLat = (req.latMin() + req.latMax()) / 2.0;
        double centerLon = (req.lonMin() + req.lonMax()) / 2.0;
        loc.setH3Index(h3IndexService.cellForPoint(centerLat, centerLon));
        userLocationRepository.save(loc);
    }

    @Transactional
    public void setActive(UUID userId, boolean active) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.setActive(active);
    }

    @Transactional
    public void deleteUser(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        userRepository.deleteById(userId);
    }

    public Optional<User> findByWhatsappNumber(String whatsappNumber) {
        return userRepository.findByWhatsappNumber(whatsappNumber);
    }

    @Transactional
    public User registerFromWhatsapp(String whatsappNumber) {
        User user = new User();
        user.setWhatsappNumber(whatsappNumber);
        return userRepository.save(user);
    }

    /**
     * Creates an anonymous web user with a ~25 km watch area around the given point.
     * Returns the device token, which is only ever available in this response.
     */
    @Transactional
    public WebRegisterResponse registerWebUser(WebRegisterRequest req) {
        String token = ApiTokens.generate();
        User user = new User();
        user.setDisplayName(req.displayName());
        user.setApiTokenHash(ApiTokens.hash(token));
        user.getLocations().add(pointLocation(user, req.latitude(), req.longitude(), labelOrHome(req.label())));
        user = userRepository.save(user);
        return new WebRegisterResponse(user.getId(), token);
    }

    /**
     * Replaces the user's active location(s) with a ~25 km box around the given point.
     * Deactivation and insert happen in one transaction so the old rows are actually flushed.
     */
    @Transactional
    public void replaceLocationFromPoint(UUID userId, double lat, double lon, String label) {
        User user = findActive(userId);
        userLocationRepository.findByUserIdAndActiveTrue(userId)
                .forEach(l -> l.setActive(false));
        userLocationRepository.save(pointLocation(user, lat, lon, labelOrHome(label)));
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        User user = findActive(userId);
        MeResponse.Location location = userLocationRepository.findByUserIdAndActiveTrue(userId).stream()
                .findFirst()
                .map(l -> new MeResponse.Location(
                        l.getLabel(),
                        (l.getLatMin() + l.getLatMax()) / 2.0,
                        (l.getLonMin() + l.getLonMax()) / 2.0,
                        DEFAULT_RADIUS_KM))
                .orElse(null);
        return new MeResponse(user.getId(), user.getDisplayName(), user.isActive(), location,
                pushSubscriptionRepository.countByUserId(userId), user.getWhatsappNumber() != null);
    }

    @Transactional(readOnly = true)
    public List<NotificationView> recentNotifications(UUID userId, int limit) {
        return notificationRepository.findByUserIdOrderBySentAtDesc(userId, PageRequest.of(0, limit)).stream()
                .map(n -> new NotificationView(
                        n.getIcao24(),
                        n.getCallsign(),
                        n.getAltitudeM() != null ? Math.round(n.getAltitudeM() * 3.28084) : null,
                        n.getSpeedMs() != null ? Math.round(n.getSpeedMs() * 3.6) : null,
                        n.getHeading() != null ? NotificationFormatter.toCompass(n.getHeading()) : null,
                        n.getChannel().name(),
                        n.getSentAt()))
                .toList();
    }

    private UserLocation pointLocation(User user, double lat, double lon, String label) {
        double deltaLat = DEFAULT_RADIUS_KM / 111.0;
        double deltaLon = DEFAULT_RADIUS_KM / (111.0 * Math.cos(Math.toRadians(lat)));

        UserLocation loc = new UserLocation();
        loc.setUser(user);
        loc.setLabel(label);
        loc.setLatMin(lat - deltaLat);
        loc.setLatMax(lat + deltaLat);
        loc.setLonMin(lon - deltaLon);
        loc.setLonMax(lon + deltaLon);
        loc.setH3Index(h3IndexService.cellForPoint(lat, lon));
        return loc;
    }

    private static String labelOrHome(String label) {
        return label == null || label.isBlank() ? "home" : label.strip();
    }

    // -------------------------------------------------------------------------

    private User findActive(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    private void validateBoundingBox(double latMin, double latMax, double lonMin, double lonMax) {
        if (latMin >= latMax) throw new IllegalArgumentException("latMin must be less than latMax");
        if (lonMin >= lonMax) throw new IllegalArgumentException("lonMin must be less than lonMax");
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getWhatsappNumber(),
                user.getDisplayName(),
                user.isActive(),
                user.getCreatedAt()
        );
    }
}
