package com.vita.store.dto.response;

import java.time.LocalDateTime;

public record StoreCreateResponse (Long storeId, String name, LocalDateTime createdAt) { }
