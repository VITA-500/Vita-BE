package com.vita.store.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record StoreNearbyItemResponse(
        Long storeId,
        String name,
        String storeType,
        String address,
        String phone,
        BigDecimal lat,
        BigDecimal lng,
        Double distanceKm,
        List<String> consultServices,
        List<String> providedServices,
        Long benefitId,
        String brand,
        String category,
        String benefitName,
        String benefitDescription) { }
