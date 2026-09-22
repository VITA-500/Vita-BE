package com.vita.faq.collection;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** FAQ 수집 실행 결과와 검토가 필요한 항목을 요약한다. */
public record LguFaqCollectionSummary(
	int visitedMainCategories,
	int visitedSubcategories,
	int fetchedPages,
	int listedFaqs,
	int requestedDetails,
	int collectedFaqs,
	List<String> failedDetailIds,
	List<String> skippedFaqIds,
	Set<String> duplicateSourceFaqIds,
	int duplicateQuestionGroups,
	Path output
) {
}
