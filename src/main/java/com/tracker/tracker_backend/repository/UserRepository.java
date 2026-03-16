package com.tracker.tracker_backend.repository;

import com.tracker.tracker_backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByWhatsappNumber(String whatsappNumber);
    boolean existsByWhatsappNumber(String whatsappNumber);
}
