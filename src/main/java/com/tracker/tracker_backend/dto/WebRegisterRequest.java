package com.tracker.tracker_backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WebRegisterRequest(
        @Size(max = 100) String displayName,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")   Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @Size(max = 100) String label
) {}
