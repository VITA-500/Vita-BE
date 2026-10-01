package com.vita.plan.dto;

import java.time.LocalDateTime;

/** 관리자 목록과 등록 응답에 필요한 필드만 포함하고 임베딩 메타데이터는 노출하지 않는다. */
public record PlanItemResponse(
        long planId,
        String planCode,
        String name,
        String summary,
        int price,
        String networkType,
        String targetGroup,
        Integer minAge,
        Integer maxAge,
        String dataPolicy,
        Long baseDataMb,
        Integer exhaustedSpeedKbps,
        String voicePolicy,
        Integer voiceMinutes,
        String smsPolicy,
        Integer smsCount,
        String description,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) { }
