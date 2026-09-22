package com.vita.faq.collection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** LG U+ FAQ 목록 API의 페이지 정보와 질문 목록 응답. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LguFaqListResponse(
	LguFaqPageInfo listPageInfo,
	@JsonProperty("lstErmsFaqDtlList") List<LguFaqListItem> items,
	@JsonProperty("lstErmsCatgTab") List<List<LguFaqCategoryTabItem>> categoryTabs
) {
	public LguFaqListResponse(LguFaqPageInfo listPageInfo, List<LguFaqListItem> items) {
		this(listPageInfo, items, List.of());
	}

	public List<LguFaqCategoryTabItem> categoriesAt(int depth) {
		if (categoryTabs == null || depth < 0 || depth >= categoryTabs.size() || categoryTabs.get(depth) == null) {
			return List.of();
		}
		return List.copyOf(categoryTabs.get(depth));
	}
}
