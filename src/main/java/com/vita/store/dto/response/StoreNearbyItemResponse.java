package com.vita.store.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record StoreNearbyItemResponse(
        Long storeId,
        String name,
        BigDecimal lat,
        BigDecimal lng,
        Double distanceKm,
        List<String> consultServices,
        List<String> providedServices) { }
