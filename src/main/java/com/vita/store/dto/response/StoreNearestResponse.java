package com.vita.store.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record StoreNearestResponse(
        Long storeId,
        String name,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        Double distanceKm,
        String businessHours,
        String phone,
        List<String> consultServices,
        List<String> providedServices) { }
