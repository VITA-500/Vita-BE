package com.vita.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * CSRF 토큰 발급 응답 (04_API명세서 0절 공통 사항).
 *
 * <p>토큰을 쿠키가 아니라 body로도 내려주는 이유 — 배포 환경은 프론트(Vercel)와 백엔드(EC2)의
 * 도메인이 달라서, 자바스크립트가 백엔드 도메인의 XSRF-TOKEN 쿠키를 읽을 수 없다. 쿠키만
 * 내려주면 프론트가 헤더에 넣을 값을 얻을 방법이 없다. body는 도메인과 무관하게 읽을 수 있다.
 *
 * <p>토큰이 응답 body에 노출되는 것은 문제가 되지 않는다. CSRF 방어는 토큰을 비밀로 지켜서
 * 성립하는 게 아니라, 다른 사이트가 우리 응답을 읽을 수 없다(CORS)는 점에 기댄다.
 */
@Schema(description = "CSRF 토큰")
public record CsrfTokenResponse(

		@Schema(description = "요청 헤더에 실을 토큰 값", example = "8f1c2e40-...")
		String token,

		@Schema(description = "토큰을 실어야 할 헤더 이름", example = "X-XSRF-TOKEN")
		String headerName
) {

	public static CsrfTokenResponse of(String token, String headerName) {
		return new CsrfTokenResponse(token, headerName);
	}
}
