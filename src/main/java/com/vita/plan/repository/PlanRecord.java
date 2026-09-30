package com.vita.plan.repository;

import java.time.LocalDateTime;

public record PlanRecord(
        long id,
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
