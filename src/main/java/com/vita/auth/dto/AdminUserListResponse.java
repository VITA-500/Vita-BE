package com.vita.auth.dto;

import com.vita.auth.Role;
import com.vita.auth.UserStatus;
import com.vita.auth.entity.User;
import com.vita.common.util.MaskingUtil;
import java.time.LocalDateTime;

public record AdminUserListResponse(Long userId, String email, String name,
    Role role, UserStatus status, LocalDateTime createdAt) {
    public static AdminUserListResponse from(User user) {
        return new AdminUserListResponse(user.getId(), MaskingUtil.email(user.getEmail()), user.getName(),
            user.getRole(), user.getStatus(), user.getCreatedAt());
    }
}
