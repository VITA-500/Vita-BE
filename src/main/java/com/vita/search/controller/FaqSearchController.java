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

@RestController
@RequestMapping("/api/search/faqs")
@RequiredArgsConstructor
public class FaqSearchController {

	private final FaqSearchService faqSearchService;

	@GetMapping
	public PageResponse<FaqSearchResult> search(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@RequestParam(required = false) String sortBy) {
		return faqSearchService.search(PageRequest.of(page, size, keyword, sortBy));
	}
}
