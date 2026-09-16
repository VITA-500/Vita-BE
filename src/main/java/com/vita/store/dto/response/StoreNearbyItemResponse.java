package com.vita.store.dto.response;

import java.math.BigDecimal;

public record StoreNearbyItemResponse(Long storeId, String name, BigDecimal lat, BigDecimal lng, Double distanceKm) { }
