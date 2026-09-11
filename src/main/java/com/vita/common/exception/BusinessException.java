package com.vita.common.exception;

import lombok.Getter;

/** 도메인 예외의 공통 상위 클래스 (unchecked). 08_개발표준: "도메인 예외는 언체크로 분리 정의". */
@Getter
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}
}
