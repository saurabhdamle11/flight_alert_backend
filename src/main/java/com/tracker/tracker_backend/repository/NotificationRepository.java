package com.tracker.tracker_backend.repository;

import com.tracker.tracker_backend.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByUserIdOrderBySentAtDesc(UUID userId, Pageable pageable);
}
