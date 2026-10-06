package com.vita.store.dto.response;

import java.time.LocalDateTime;

public record AdminBenefitResponse(
        Long benefitId,
        String brand,
        String name,
        String category,
        String description,
        long storeCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) { }
