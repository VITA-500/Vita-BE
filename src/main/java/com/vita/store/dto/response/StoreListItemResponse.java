package com.vita.store.dto.response;

import java.time.LocalDateTime;

public record StoreListItemResponse(
        Long storeId,
        String name,
        String address,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) { }
