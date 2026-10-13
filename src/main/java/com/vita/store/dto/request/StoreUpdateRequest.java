package com.vita.store.dto.request;

import com.vita.store.entity.BusinessHours;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

/** 매장 수정. 유형(storeType)은 바꿀 수 없고, benefitId는 제휴 매장에서 보냈을 때만 브랜드 변경 */
public record StoreUpdateRequest (
        @NotBlank String name,
        @NotBlank String address,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lng,
        @Pattern(regexp = BusinessHours.FORMAT, message = "영업시간은 HH:mm-HH:mm 형식이어야 합니다. (예: 10:00-20:00)")
        String businessHours,
        String phone,
        List<@NotBlank String> consultServices,
        List<@NotBlank String> providedServices,
        Long benefitId) { }
