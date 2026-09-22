package com.vita.faq.collection;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** FAQ 식별자 중복 제거 결과와 질문 중복 검토 후보. */
public record LguFaqDeduplicationResult(
	List<LguFaqCollectedRecord> uniqueFaqs,
	Set<String> duplicateSourceFaqIds,
	Map<String, List<LguFaqCollectedRecord>> duplicateQuestionCandidates
) {
}
