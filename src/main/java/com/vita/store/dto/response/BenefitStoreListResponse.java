package com.vita.store.dto.response;

import java.math.BigDecimal;
import java.util.List;

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
            Long benefitId,
            String brand,
            String category,
            String benefitName) {}
}
