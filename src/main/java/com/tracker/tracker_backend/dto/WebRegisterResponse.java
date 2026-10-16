package com.tracker.tracker_backend.dto;

import java.util.UUID;

/** The token is returned only once; the browser stores it and sends it as a Bearer token. */
public record WebRegisterResponse(UUID userId, String token) {}
