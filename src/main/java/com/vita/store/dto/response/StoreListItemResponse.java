package com.vita.store.dto.response;

import java.time.LocalDateTime;

public record StoreListItemResponse(
        Long storeId,
        String name,
        String address,
        String storeType,
        Long benefitId,                     // 제휴 매장만. 연결된 브랜드 id, 통신 매장은 null
        String brand,
        String category,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) { }
