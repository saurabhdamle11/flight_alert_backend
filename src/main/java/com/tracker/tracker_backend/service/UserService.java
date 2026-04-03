package com.tracker.tracker_backend.service;

import com.tracker.tracker_backend.dto.CreateUserRequest;
import com.tracker.tracker_backend.dto.UpdateLocationRequest;
import com.tracker.tracker_backend.dto.UserResponse;
import com.tracker.tracker_backend.entity.User;
import com.tracker.tracker_backend.entity.UserLocation;
import com.tracker.tracker_backend.exception.DuplicateWhatsappNumberException;
import com.tracker.tracker_backend.exception.UserNotFoundException;
import com.tracker.tracker_backend.repository.UserLocationRepository;
import com.tracker.tracker_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserLocationRepository userLocationRepository;
    private final H3IndexService h3IndexService;

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

    @Transactional
    public void addLocationFromPoint(User user, double lat, double lon) {
        // ~25 km radius bounding box
        double deltaLat = 25.0 / 111.0;
        double deltaLon = 25.0 / (111.0 * Math.cos(Math.toRadians(lat)));

        UserLocation loc = new UserLocation();
        loc.setUser(user);
        loc.setLabel("home");
        loc.setLatMin(lat - deltaLat);
        loc.setLatMax(lat + deltaLat);
        loc.setLonMin(lon - deltaLon);
        loc.setLonMax(lon + deltaLon);
        loc.setH3Index(h3IndexService.cellForPoint(lat, lon));
        userLocationRepository.save(loc);
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
