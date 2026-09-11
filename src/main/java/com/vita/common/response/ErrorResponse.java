package com.vita.common.response;

import com.vita.common.exception.ErrorCode;
import java.util.List;

/**
 * 실패 응답 공통 포맷 — 04_API명세서 0절 기준: {"code": "...", "message": "..."} (wrapper 없음, 2026-09-11 확정).
 * 검증 실패(VALIDATION_ERROR)는 fieldErrors에 필드별 에러를 전부 담아서 반환한다 — 그 외 에러는 null.
 */
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {

	public record FieldError(String field, String message) {
	}

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null);
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.name(), message, null);
	}

	public static ErrorResponse ofValidation(List<FieldError> fieldErrors) {
		return new ErrorResponse(ErrorCode.VALIDATION_ERROR.name(), ErrorCode.VALIDATION_ERROR.getMessage(), fieldErrors);
	}
}
