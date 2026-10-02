package com.vita.store.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record StoreDetailResponse (
        Long storeId,
        String name,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        String businessHours,
        String phone,
        List<String> consultServices,
        List<String> providedServices,
        String storeType,
        Long benefitId,
        String brand,
        String category,
        String benefitName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) { }
