package com.vita.store.repository;

import java.math.BigDecimal;

public interface PartnerStoreDistanceProjection {
    Long getId();
    String getName();
    String getAddress();
    BigDecimal getLat();
    BigDecimal getLng();
    Double getDistanceKm();
    Long getBenefitId();
    String getBrand();
    String getCategory();
    String getBenefitName();
}
