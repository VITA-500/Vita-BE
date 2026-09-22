package com.vita.faq.collection;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 동일한 LG U+ FAQ 식별자를 제거하고 질문 중복 후보를 찾는다. */
@Component
public class LguFaqDeduplicator {

	public LguFaqDeduplicationResult deduplicate(List<LguFaqCollectedRecord> faqs) {
		if (faqs == null || faqs.isEmpty()) {
			return new LguFaqDeduplicationResult(List.of(), Set.of(), Map.of());
		}

		Map<String, LguFaqCollectedRecord> uniqueBySourceId = new LinkedHashMap<>();
		Set<String> duplicateSourceIds = new LinkedHashSet<>();
		for (LguFaqCollectedRecord faq : faqs) {
			if (faq == null) {
				continue;
			}
			if (uniqueBySourceId.putIfAbsent(faq.sourceFaqId(), faq) != null) {
				duplicateSourceIds.add(faq.sourceFaqId());
			}
		}

		Map<String, List<LguFaqCollectedRecord>> groupedByQuestion = new LinkedHashMap<>();
		for (LguFaqCollectedRecord faq : uniqueBySourceId.values()) {
			groupedByQuestion.computeIfAbsent(normalizeQuestion(faq.question()), ignored -> new ArrayList<>())
				.add(faq);
		}
		groupedByQuestion.entrySet().removeIf(entry -> entry.getValue().size() < 2);

		Map<String, List<LguFaqCollectedRecord>> duplicateQuestions = new LinkedHashMap<>();
		groupedByQuestion.forEach((question, candidates) ->
			duplicateQuestions.put(question, List.copyOf(candidates))
		);

		return new LguFaqDeduplicationResult(
			List.copyOf(uniqueBySourceId.values()),
			Collections.unmodifiableSet(new LinkedHashSet<>(duplicateSourceIds)),
			Collections.unmodifiableMap(new LinkedHashMap<>(duplicateQuestions))
		);
	}

	private String normalizeQuestion(String question) {
		return question == null ? "" : question.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}
}
