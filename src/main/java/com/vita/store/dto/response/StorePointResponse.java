package com.vita.store.dto.response;

import java.math.BigDecimal;

/** 이름으로 찾은 매장의 위치 (채팅에서 매장 이름으로 물을 때 기준점) */
public record StorePointResponse(
        Long storeId,
        String name,
        String storeType,
        BigDecimal lat,
        BigDecimal lng) { }
