package com.vita.store.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BenefitRequest(
        @NotBlank @Size(max = 50) String brand,
        @NotBlank @Size(max = 100) String name,
        @NotBlank String category,
        String description) { }
