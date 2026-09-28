package com.vita.store.dto.response;

import java.time.LocalDateTime;

public record StoreUpdateResponse(Long storeId, LocalDateTime updatedAt) {
}
