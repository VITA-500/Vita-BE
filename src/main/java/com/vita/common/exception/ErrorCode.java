package com.vita.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 전역 공통 에러 코드. 04_API명세서 "공통 에러 코드" 기준.
 * 각자 도메인 에러가 필요하면 여기 값을 추가하고, 도메인 폴더 루트에 BusinessException을 상속한
 * 전용 예외 클래스를 만들어 사용한다 (08_개발표준 2절).
 */
@Getter
public enum ErrorCode {

	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	LOCATION_REQUIRED(HttpStatus.BAD_REQUEST, "위치 정보가 필요합니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

	// 인증 (auth) — 04_API명세서 1절 기준
	EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
	/**
	 * 이메일이 없는 경우와 비밀번호가 틀린 경우를 구분하지 않는다 — 구분해서 응답하면
	 * 공격자가 어떤 이메일이 가입되어 있는지 알아낼 수 있다.
	 */
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String message;

	ErrorCode(HttpStatus httpStatus, String message) {
		this.httpStatus = httpStatus;
		this.message = message;
	}
}
