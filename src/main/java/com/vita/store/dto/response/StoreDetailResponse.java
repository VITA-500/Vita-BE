package com.vita.store.dto.response;

import java.math.BigDecimal;
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
        List<String> providedServices) { }
