package com.vita.store.dto.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * List<@NotBlank String>은 빈 문자열 원소를 400으로 막는다.
 **/
public record StoreCreateRequest(
        @NotBlank String name,
        @NotBlank String address,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lng,
        String businessHours,
        String phone,
        List<@NotBlank String> consultServices,
        List<@NotBlank String> providedServices,
        @Pattern(regexp = "PHONE|PARTNER", message = "storeType은 PHONE 또는 PARTNER여야 합니다.")
        String storeType) { }
