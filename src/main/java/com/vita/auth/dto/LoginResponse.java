package com.vita.auth.dto;

import com.vita.auth.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 04_API명세서 1.2 로그인 응답.
 *
 * <p>accessToken은 body가 아니라 HttpOnly 쿠키로 내려간다(FE1 협의, 2026-09-17). 자바스크립트가
 * 토큰을 읽지 못하게 해 XSS로 탈취되는 경로를 막기 위함이다. 소셜 로그인도 같은 방식이라
 * 프론트는 로그인 경로와 무관하게 동일하게 처리하면 된다.
 *
 * <p>refreshToken은 Phase 2 범위라 아직 발급하지 않는다.
 */
@Schema(description = "로그인 성공 응답")
public record LoginResponse(

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

	public static LoginResponse from(User user) {
		return new LoginResponse(
				new UserInfo(user.getId(), user.getEmail(), user.getName(), user.getRole().name())
		);
	}
}
