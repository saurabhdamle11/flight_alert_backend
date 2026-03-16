package com.tracker.tracker_backend.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String whatsappNumber,
        String displayName,
        boolean active,
        Instant createdAt
) {}
