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
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

	private final HttpStatus httpStatus;
	private final String message;

	ErrorCode(HttpStatus httpStatus, String message) {
		this.httpStatus = httpStatus;
		this.message = message;
	}
}
