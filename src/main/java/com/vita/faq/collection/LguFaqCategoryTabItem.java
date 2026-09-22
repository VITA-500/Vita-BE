package com.vita.faq.collection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** LG U+ FAQ 화면의 Category 탭 ID와 공식 표시명. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LguFaqCategoryTabItem(
	String id,
	String name
) {
}
