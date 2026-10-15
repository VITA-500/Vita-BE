package com.vita.store.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** 업종별 제휴 매장 거리순 (GET /benefits/{category}/stores) */
public record BenefitStoreListResponse(
        String category,
        List<Item> stores) {

    public record Item(
            Long storeId,
            String name,
            String address,
            BigDecimal lat,
            BigDecimal lng,
            double distanceKm,
            String businessHours,
            Boolean openNow,
            Long benefitId,
            String brand,
            String category,
            String benefitName) {}
}
