package com.vita.store.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** 채팅용 주변 통신 매장 */
public record StoreChatItemResponse(
        Long storeId,
        String name,
        String address,
        String phone,
        BigDecimal lat,
        BigDecimal lng,
        Double distanceKm,
        String businessHours,
        Boolean openNow,
        List<String> consultServices,
        List<String> providedServices) { }
