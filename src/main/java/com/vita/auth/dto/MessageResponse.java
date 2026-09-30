package com.vita.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 돌려줄 데이터 없이 처리 결과만 알리는 응답 (04_API명세서: 로그아웃, 토큰 재발급). */
@Schema(description = "처리 결과 메시지")
public record MessageResponse(
		@Schema(description = "결과 메시지", example = "로그아웃되었습니다.")
		String message
) {
	public static MessageResponse of(String message) {
		return new MessageResponse(message);
	}
}
