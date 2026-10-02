package com.vita.store.dto.response;

public record BenefitResponse(
        Long benefitId,
        String brand,
        String name,                    // 혜택명
        String category,                // 카페 / 아이스크림 / 영화 / 외식 / 자동차 / 쇼핑 / 여기
        String description,
        long storeCount) { }
