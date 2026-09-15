package com.vita.search.dto;

import com.vita.search.entity.Faq;

public record FaqSearchResult(Long id, String category, String subcategory, String question, String answer) {

	public static FaqSearchResult from(Faq faq) {
		return new FaqSearchResult(faq.getId(), faq.getCategory(), faq.getSubcategory(), faq.getQuestion(), faq.getAnswer());
	}
}
