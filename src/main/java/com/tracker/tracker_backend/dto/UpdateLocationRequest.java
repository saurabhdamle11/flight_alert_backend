package com.tracker.tracker_backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record UpdateLocationRequest(
        String label,

        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")  Double latMin,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")  Double latMax,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lonMin,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lonMax
) {}
