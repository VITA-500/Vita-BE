package com.vita.store.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** 지도 주변 매장. 통신 및 제휴 공통 모양 (통신은 혜택 칸 null, 제휴는 서비스 목록이 빈 배열) */
public record StoreNearbyItemResponse(
        Long storeId,
        String name,
        String storeType,
        String address,
        String phone,
        BigDecimal lat,
        BigDecimal lng,
        Double distanceKm,
        String businessHours,
        Boolean openNow,
        List<String> consultServices,
        List<String> providedServices,
        Long benefitId,
        String brand,
        String category,
        String benefitName,
        String benefitDescription) { }
