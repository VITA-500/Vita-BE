package com.vita.faq.collection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** LG U+ FAQ 목록 응답의 페이지 정보를 나타낸다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LguFaqPageInfo(
	int pageSize,
	int rowSize,
	int pageNo,
	int totalPage,
	int totalCount
) {
}
