package com.vita.store.repository;

import java.math.BigDecimal;

/** 통신 매장 거리 계산 쿼리 결과 (StoreRepository native 쿼리) */
public interface StoreDistanceProjection {

    Long getId();

    String getName();

    String getAddress();

    BigDecimal getLat();

    BigDecimal getLng();

    String getBusinessHours();

    String getPhone();

    Double getDistanceKm();

    /** 서비스 목록은 "||"로 이어 붙인 글자 */
    String getConsultServices();

    String getProvidedServices();
}
