package com.vita.search.controller;

import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.search.dto.FaqSearchResult;
import com.vita.search.service.FaqSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FAQ 키워드 검색 API. 인증 불필요 (SecurityConfig.PUBLIC_PATHS의 /search/**). */
@RestController
@RequestMapping("/search/faqs")
@RequiredArgsConstructor
public class FaqSearchController {

	private final FaqSearchService faqSearchService;

	/** keyword로 ACTIVE FAQ를 검색한다. keyword 미지정 시 400. */
	@GetMapping
	public PageResponse<FaqSearchResult> search(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@RequestParam(required = false) String sortBy) {
		return faqSearchService.search(PageRequest.of(page, size, keyword, sortBy));
	}
}
