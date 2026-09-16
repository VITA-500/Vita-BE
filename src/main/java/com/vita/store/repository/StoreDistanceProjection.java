package com.vita.store.repository;

import java.math.BigDecimal;

public interface StoreDistanceProjection {

    Long getId();

    String getName();

    String getAddress();

    BigDecimal getLat();

    BigDecimal getLng();

    String getBusinessHours();

    String getPhone();

    Double getDistanceKm();
}
