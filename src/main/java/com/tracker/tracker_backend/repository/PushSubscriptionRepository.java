package com.tracker.tracker_backend.repository;

import com.tracker.tracker_backend.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {
    List<PushSubscription> findByUserId(UUID userId);
    Optional<PushSubscription> findByEndpoint(String endpoint);
    long countByUserId(UUID userId);
    long deleteByUserIdAndEndpoint(UUID userId, String endpoint);
}
