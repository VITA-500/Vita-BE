package com.vita.plan.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record PlanCreateRequest(
        @NotBlank @Size(max = 50) String planCode,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 255) String summary,
        @NotNull @PositiveOrZero Integer price,
        @NotBlank @Size(max = 20) String networkType,
        @NotBlank @Size(max = 20) String targetGroup,
        @PositiveOrZero Integer minAge,
        @PositiveOrZero Integer maxAge,
        @NotBlank @Size(max = 20) String dataPolicy,
        @Positive Long baseDataMb,
        @Positive Integer exhaustedSpeedKbps,
        @NotBlank @Size(max = 20) String voicePolicy,
        @Positive Integer voiceMinutes,
        @NotBlank @Size(max = 20) String smsPolicy,
        @Positive Integer smsCount,
        @NotBlank String description
) {
    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw new IllegalArgumentException("등록 요청에 허용되지 않는 필드입니다: " + name);
    }
}
