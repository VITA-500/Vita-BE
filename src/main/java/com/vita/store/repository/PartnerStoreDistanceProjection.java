package com.vita.store.repository;

import java.math.BigDecimal;

/** 제휴 매장 거리 계산 쿼리 결과 + 연결된 혜택 정보 */
public interface PartnerStoreDistanceProjection {
    Long getId();
    String getName();
    String getAddress();
    String getPhone();
    String getBusinessHours();
    BigDecimal getLat();
    BigDecimal getLng();
    Double getDistanceKm();
    Long getBenefitId();
    String getBrand();
    String getCategory();
    String getBenefitName();
    String getBenefitDescription();
}
