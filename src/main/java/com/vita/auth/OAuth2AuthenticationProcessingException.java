package com.vita.auth;

import com.vita.common.exception.ErrorCode;
import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/**
 * 소셜 로그인 처리 중 발생한 도메인 예외 (08_개발표준 2절 — 도메인 예외는 언체크로 분리 정의).
 *
 * <p>BusinessException이 아니라 AuthenticationException을 상속하는 이유: Spring Security의
 * OAuth2 필터 체인 안에서 던져지므로, 이 타입이어야 failureHandler로 전달된다.
 * BusinessException으로 던지면 GlobalExceptionHandler까지 가지 못하고 500이 된다.
 */
@Getter
public class OAuth2AuthenticationProcessingException extends AuthenticationException {

	private final ErrorCode errorCode;

	/** 메시지는 ErrorCode의 것을 쓴다. FailureHandler가 errorCode를 꺼내 프론트로 전달한다. */
	public OAuth2AuthenticationProcessingException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
