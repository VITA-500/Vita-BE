package com.vita.auth.dto;

import com.vita.auth.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 04_API명세서 1.1 — 가입 결과를 201과 함께 반환한다. */
@Schema(description = "회원가입 결과")
public record SignupResponse(

		@Schema(description = "생성된 사용자 ID", example = "1")
		Long userId,

		@Schema(description = "가입 이메일", example = "user@example.com")
		String email,

		@Schema(description = "이름", example = "김어진")
		String name,

		@Schema(description = "가입 시각 (KST)", example = "2026-09-10T10:00:00")
		LocalDateTime createdAt
) {

	public static SignupResponse from(User user) {
		return new SignupResponse(user.getId(), user.getEmail(), user.getName(), user.getCreatedAt());
	}
}
