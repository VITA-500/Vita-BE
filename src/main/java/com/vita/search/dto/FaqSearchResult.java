package com.vita.search.dto;

import com.vita.search.entity.Faq;

/** 키워드 검색 API(GET /search/faqs) 응답 항목. Faq 엔티티를 그대로 노출하지 않기 위한 DTO. */
public record FaqSearchResult(Long id, String category, String subcategory, String question, String answer) {

	/** Faq 엔티티를 응답 DTO로 변환한다. */
	public static FaqSearchResult from(Faq faq) {
		return new FaqSearchResult(faq.getId(), faq.getCategory(), faq.getSubcategory(), faq.getQuestion(), faq.getAnswer());
	}
}
