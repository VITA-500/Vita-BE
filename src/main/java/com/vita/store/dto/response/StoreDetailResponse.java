package com.vita.store.dto.response;

import java.math.BigDecimal;

public record StoreDetailResponse (
        Long storeId,
        String name,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        String businessHours,
        String phone) { }
