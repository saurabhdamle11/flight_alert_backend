package com.tracker.tracker_backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateUserRequest(
        @NotBlank
        @Pattern(regexp = "\\+[1-9]\\d{7,14}", message = "Must be a valid E.164 phone number")
        String whatsappNumber,

        String displayName,

        @Valid
        BoundingBoxRequest location
) {}
