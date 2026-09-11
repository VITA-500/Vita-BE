package com.vita.common.page;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** 관리자 CRUD 목록 API 공통 응답 — 04_API명세서 0절 기준 (content/totalCount/totalPages/currentPage). */
public record PageResponse<T>(List<T> content, long totalCount, int totalPages, int currentPage) {

	public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
		return new PageResponse<>(
				page.getContent().stream().map(mapper).toList(),
				page.getTotalElements(),
				page.getTotalPages(),
				page.getNumber());
	}
}
