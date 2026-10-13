package com.vita.auth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long actorId;
    @Column(nullable = false) private Long targetId;
    @Column(nullable = false, length = 30) private String action;
    @Column(nullable = false, length = 500) private String reason;
    @Column(nullable = false, length = 100) private String changedFields;
    @Column(length = 100) private String beforeState;
    @Column(nullable = false, length = 100) private String afterState;
    @Column(nullable = false) private LocalDateTime createdAt;

    public UserAuditLog(Long actorId, User target, String action, String reason, String fields, String before) {
        this.actorId = actorId;
        this.targetId = target.getId();
        this.action = action;
        this.reason = reason;
        this.changedFields = fields;
        this.beforeState = before;
        this.afterState = stateOf(target);
        this.createdAt = LocalDateTime.now();
    }

    /** 개인정보/비밀번호를 복제하지 않고 권한·상태와 변경 필드만 보관한다. */
    public static String stateOf(User user) { return user.getRole().name() + "/" + user.getStatus().name(); }
}
