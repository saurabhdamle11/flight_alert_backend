package com.tracker.tracker_backend.repository;

import com.tracker.tracker_backend.entity.UserLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface UserLocationRepository extends JpaRepository<UserLocation, UUID> {

    List<UserLocation> findByUserIdAndActiveTrue(UUID userId);

    @Query("""
            SELECT ul FROM UserLocation ul
            JOIN FETCH ul.user u
            WHERE ul.active = true
              AND u.active = true
              AND ul.latMin <= :lat AND ul.latMax >= :lat
              AND ul.lonMin <= :lon AND ul.lonMax >= :lon
            """)
    List<UserLocation> findActiveLocationsContaining(double lat, double lon);
}
