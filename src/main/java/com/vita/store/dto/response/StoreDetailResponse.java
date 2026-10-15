package com.vita.store.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 매장 상세. 혜택 칸(benefitId~benefitName)은 제휴 매장만 */
public record StoreDetailResponse (
        Long storeId,
        String name,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        String businessHours,
        Boolean openNow,
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
