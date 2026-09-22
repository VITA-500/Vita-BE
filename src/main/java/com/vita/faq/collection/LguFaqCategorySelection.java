package com.vita.faq.collection;

/** FAQ 수집에 사용할 공식 메인 Category와 직접 Subcategory. */
public record LguFaqCategorySelection(
	String mainCategoryId,
	String mainCategoryName,
	String subcategoryId,
	String subcategoryName
) {
}
