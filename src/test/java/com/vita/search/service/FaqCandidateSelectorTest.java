package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vita.search.dto.FaqSimilarityResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaqCandidateSelectorTest {

	private static FaqSimilarityResult faq(long id, String category, String subcategory, String answer, double similarity) {
		return new FaqSimilarityResult(id, category, subcategory, "질문" + id, answer, similarity, null);
	}

	@Test
	void keepsOnlyMostSimilarAmongSameAnswerVariants() {
		List<FaqSimilarityResult> candidates = List.of(
				faq(1, "모바일", "요금제", "답변A", 0.95),
				faq(2, "모바일", "요금제", "답변A", 0.94),
				faq(3, "모바일", "요금제", "답변A", 0.93),
				faq(4, "모바일", "요금제", "답변B", 0.90));

		List<FaqSimilarityResult> result = FaqCandidateSelector.selectDistinct(candidates, 3);

		assertThat(result).extracting(FaqSimilarityResult::id).containsExactly(1L, 4L);
	}

	@Test
	void keepsBothWhenSubcategoryDiffersEvenIfAnswerIsSame() {
		List<FaqSimilarityResult> candidates = List.of(
				faq(1, "소상공인", "CCTV", "같은 답변", 0.95),
				faq(2, "소상공인", "IPTV", "같은 답변", 0.94));

		List<FaqSimilarityResult> result = FaqCandidateSelector.selectDistinct(candidates, 3);

		assertThat(result).extracting(FaqSimilarityResult::id).containsExactly(1L, 2L);
	}

	@Test
	void cutsToTopKAfterRemovingDuplicatesAndKeepsSimilarityOrder() {
		List<FaqSimilarityResult> candidates = List.of(
				faq(1, "가", "a", "A", 0.95),
				faq(2, "가", "a", "A", 0.94),
				faq(3, "나", "b", "B", 0.93),
				faq(4, "다", "c", "C", 0.92),
				faq(5, "라", "d", "D", 0.91));

		List<FaqSimilarityResult> result = FaqCandidateSelector.selectDistinct(candidates, 3);

		assertThat(result).extracting(FaqSimilarityResult::id).containsExactly(1L, 3L, 4L);
	}

	@Test
	void returnsFewerThanTopKWhenNotEnoughDistinctAnswers() {
		List<FaqSimilarityResult> candidates = List.of(
				faq(1, "가", "a", "A", 0.95),
				faq(2, "가", "a", "A", 0.94));

		assertThat(FaqCandidateSelector.selectDistinct(candidates, 3)).hasSize(1);
		assertThat(FaqCandidateSelector.selectDistinct(List.of(), 3)).isEmpty();
	}

	@Test
	void handlesNullSubcategory() {
		List<FaqSimilarityResult> candidates = List.of(
				faq(1, "가", null, "A", 0.95),
				faq(2, "가", null, "A", 0.94),
				faq(3, "가", "a", "A", 0.93));

		List<FaqSimilarityResult> result = FaqCandidateSelector.selectDistinct(candidates, 3);

		assertThat(result).extracting(FaqSimilarityResult::id).containsExactly(1L, 3L);
	}

	@Test
	void poolSizeIsMultipleOfTopK() {
		assertThat(FaqCandidateSelector.poolSize(3)).isEqualTo(3 * FaqCandidateSelector.POOL_MULTIPLIER);
	}
}
