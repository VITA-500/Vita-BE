package com.vita.auth.dto;

import com.vita.auth.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 04_API명세서 1.2 로그인 응답.
 *
 * <p>명세에는 refreshToken도 포함되어 있으나 Phase 1 범위가 아니라 아직 발급하지 않는다 —
 * 필드를 내려보내면 FE가 있는 것으로 오해하므로 아예 넣지 않았다. Phase 2에서 추가한다.
 */
@Schema(description = "로그인 성공 응답")
public record LoginResponse(

		@Schema(description = "이후 API 호출 시 Authorization 헤더에 'Bearer {값}' 형태로 넣는다",
				example = "eyJhbGciOiJIUzI1NiIs...")
		String accessToken,

		@Schema(description = "로그인한 사용자 정보")
		UserInfo user
) {

	@Schema(description = "로그인 사용자")
	public record UserInfo(

			@Schema(description = "사용자 ID", example = "1")
			Long userId,

			@Schema(description = "이메일. 소셜 가입자는 null일 수 있다", example = "user@example.com")
			String email,

			@Schema(description = "이름", example = "김어진")
			String name,

			@Schema(description = "권한", example = "USER")
			String role
	) {
	}

	public static LoginResponse of(String accessToken, User user) {
		return new LoginResponse(
				accessToken,
				new UserInfo(user.getId(), user.getEmail(), user.getName(), user.getRole().name())
		);
	}
}
