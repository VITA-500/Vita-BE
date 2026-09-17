package com.vita.faq.collection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** LG U+ FAQ 목록 API의 페이지 정보와 질문 목록 응답. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LguFaqListResponse(
	LguFaqPageInfo listPageInfo,
	@JsonProperty("lstErmsFaqDtlList") List<LguFaqListItem> items
) {
}
