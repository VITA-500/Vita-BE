package com.vita.store.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record StoreChatItemResponse(
        Long storeId,
        String name,
        String address,
        String phone,
        BigDecimal lat,
        BigDecimal lng,
        Double distanceKm,
        String businessHours,
        List<String> consultServices,
        List<String> providedServices) { }
