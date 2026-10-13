package com.vita.auth.dto;

import com.vita.auth.entity.UserAuditLog;
import java.time.LocalDateTime;

public record UserAuditResponse(Long id, Long actorId, Long targetId, String action, String reason,
    String changedFields, String beforeState, String afterState, LocalDateTime createdAt) {
    public static UserAuditResponse from(UserAuditLog log) {
        return new UserAuditResponse(log.getId(), log.getActorId(), log.getTargetId(), log.getAction(),
            log.getReason(), log.getChangedFields(), log.getBeforeState(), log.getAfterState(), log.getCreatedAt());
    }
}
