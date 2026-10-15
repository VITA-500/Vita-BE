package com.vita.store.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 혜택 등록 및 수정. category는 Benefit.CATEGORIES 중 하나 */
public record BenefitRequest(
        @NotBlank @Size(max = 50) String brand,
        @NotBlank @Size(max = 100) String name,
        @NotBlank String category,
        String description) { }
