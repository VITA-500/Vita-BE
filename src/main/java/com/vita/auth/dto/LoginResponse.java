package com.vita.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 성공 응답")
public record LoginResponse(

		@Schema(description = "이후 API 호출 시 Authorization 헤더에 'Bearer {값}' 형태로 넣는다",
				example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
		String accessToken,

		@Schema(description = "토큰 유효기간(초)", example = "3600")
		long expiresIn,

		@Schema(description = "사용자 이름", example = "홍길동")
		String name
) {
}
