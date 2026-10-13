package com.vita.auth.dto;

import com.vita.auth.Role;
import com.vita.auth.UserStatus;
import com.vita.auth.entity.User;
import java.time.LocalDateTime;

/** 명시적인 응답 DTO로 passwordHash, tokenVersion, ORM 필드의 노출을 방지한다. */
public record AdminUserResponse(Long userId, String email, String name, String phone,
    Role role, UserStatus status, boolean hasPassword, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getName(), user.getPhone(),
            user.getRole(), user.getStatus(), user.hasPassword(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
