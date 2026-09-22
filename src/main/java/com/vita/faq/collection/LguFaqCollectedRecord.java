package com.vita.faq.collection;

/** LG U+ 상세 응답을 정제하고 프로젝트 분류를 적용한 FAQ 한 건. */
public record LguFaqCollectedRecord(
	String sourceFaqId,
	String category,
	String subcategory,
	String question,
	String answer,
	String sourcePath
) {
}
