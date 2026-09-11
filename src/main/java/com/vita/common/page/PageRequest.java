package com.vita.common.page;

/** 관리자 CRUD 목록 API 공통 요청 파라미터 — 04_API명세서 0절 "공통 페이징 요청/응답" 기준. */
public record PageRequest(int page, int size, String keyword, String sortBy) {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 20;

	public static PageRequest of(Integer page, Integer size, String keyword, String sortBy) {
		return new PageRequest(
				page != null ? page : DEFAULT_PAGE,
				size != null ? size : DEFAULT_SIZE,
				keyword,
				sortBy);
	}

	public org.springframework.data.domain.PageRequest toSpringPageRequest() {
		return org.springframework.data.domain.PageRequest.of(page, size);
	}
}
