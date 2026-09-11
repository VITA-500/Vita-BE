package com.vita.common.response;

/** 성공 응답 공통 포맷 — 04_API명세서 0절 "공통 응답 포맷" 기준: {"success": true, "data": {...}}. */
public record ApiResponse<T>(boolean success, T data) {

	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(true, data);
	}
}
