package com.vita.common.response;

import com.vita.common.exception.ErrorCode;

/** 실패 응답 공통 포맷 — 04_API명세서 0절 기준: {"success": false, "code": "...", "message": "..."}. */
public record ErrorResponse(boolean success, String code, String message) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(false, errorCode.name(), errorCode.getMessage());
	}
}
