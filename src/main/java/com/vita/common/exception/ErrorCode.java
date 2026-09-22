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
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
	/**
	 * 소셜 전용 계정이 비밀번호 변경을 시도한 경우. 비밀번호가 애초에 없는 계정이라 "변경"이
	 * 성립하지 않는다. FE는 hasPassword=false면 변경 UI를 숨기지만, API를 직접 부를 수 있어 막는다.
	 */
	PASSWORD_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "비밀번호를 설정할 수 없는 계정입니다."),

	// 소셜 로그인 — FE가 각각 다른 화면/안내를 보여줘야 해서 별도 코드로 둔다.
	/** 소셜 계정의 이메일이 이미 자체 가입된 계정과 겹칠 때. 자동 연결은 계정 탈취 경로가 되므로 거부한다. */
	OAUTH_EMAIL_CONFLICT(HttpStatus.CONFLICT, "이미 가입된 이메일입니다. 일반 로그인을 이용해주세요."),
	/** 사용자가 동의 화면에서 취소했거나 제공자 인증이 실패한 경우. */
	OAUTH_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "소셜 로그인에 실패했습니다.");

	private final HttpStatus httpStatus;
	private final String message;

	ErrorCode(HttpStatus httpStatus, String message) {
		this.httpStatus = httpStatus;
		this.message = message;
	}
}
