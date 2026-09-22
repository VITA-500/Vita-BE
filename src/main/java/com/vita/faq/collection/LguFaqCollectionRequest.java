package com.vita.faq.collection;

import java.nio.file.Path;

/** FAQ 자동 수집 범위와 JSONL 출력 위치. */
public record LguFaqCollectionRequest(
	String mainCategory,
	int pageSize,
	int maxMainCategories,
	int maxSubcategoriesPerMain,
	int maxPagesPerSubcategory,
	int maxDetails,
	long requestDelayMillis,
	Path output
) {

	public LguFaqCollectionRequest {
		if (mainCategory == null) {
			throw new IllegalArgumentException("FAQ 메인 Category 필터는 null일 수 없습니다.");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new IllegalArgumentException("페이지당 FAQ 수는 1 이상 100 이하여야 합니다.");
		}
		if (maxMainCategories < 1) {
			throw new IllegalArgumentException("최대 메인 Category 수는 1 이상이어야 합니다.");
		}
		if (maxSubcategoriesPerMain < 1) {
			throw new IllegalArgumentException("메인별 최대 Subcategory 수는 1 이상이어야 합니다.");
		}
		if (maxPagesPerSubcategory < 1) {
			throw new IllegalArgumentException("최대 페이지 수는 1 이상이어야 합니다.");
		}
		if (maxDetails < 1) {
			throw new IllegalArgumentException("최대 상세 조회 수는 1 이상이어야 합니다.");
		}
		if (requestDelayMillis < 0 || requestDelayMillis > 60_000) {
			throw new IllegalArgumentException("요청 간격은 0 이상 60000ms 이하여야 합니다.");
		}
		if (output == null) {
			throw new IllegalArgumentException("FAQ JSONL 출력 경로가 필요합니다.");
		}
		mainCategory = mainCategory.strip();
	}
}
