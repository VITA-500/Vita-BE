package com.vita.search.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.EnglishFaqVectorSearchRepository;
import com.vita.search.repository.EnglishPlanVectorSearchRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnglishRetrieversTest {

	private final EnglishFaqVectorSearchRepository faqRepository = mock(EnglishFaqVectorSearchRepository.class);
	private final EnglishPlanVectorSearchRepository planRepository = mock(EnglishPlanVectorSearchRepository.class);

	@Test
	void faqRetrieverSearchesActiveEnglishEmbeddingsWithoutAThresholdAndKeepsTheOrder() {
		float[] vector = {0.1f, 0.2f};
		List<FaqSimilarityResult> found = List.of(faq(11L, 0.91), faq(7L, 0.84));
		when(faqRepository.searchBySimilarity(vector, FaqStatus.ACTIVE, 0.0, 30)).thenReturn(found);

		List<FaqSimilarityResult> result = new EnglishFaqRetriever(faqRepository)
				.retrieve(new RetrievalQuery("How do I change my plan?", vector), 30);

		// threshold는 여기서 걸지 않고(0.0), 리포지토리가 돌려준 순서와 유사도를 그대로 전달한다.
		assertThat(result).isSameAs(found);
		verify(faqRepository).searchBySimilarity(vector, FaqStatus.ACTIVE, 0.0, 30);
	}

	@Test
	void planRetrieverSearchesEnglishEmbeddingsWithoutAThresholdAndKeepsTheOrder() {
		float[] vector = {0.3f, 0.4f};
		List<PlanSimilarityResult> found = List.of(plan("VITA-MAX", 0.90), plan("VITA-LITE-5", 0.82));
		when(planRepository.searchBySimilarity(vector, 0.0, 50)).thenReturn(found);

		List<PlanSimilarityResult> result = new EnglishPlanRetriever(planRepository)
				.retrieve(new RetrievalQuery("Which plan has unlimited data?", vector), 50);

		assertThat(result).isSameAs(found);
		verify(planRepository).searchBySimilarity(vector, 0.0, 50);
	}

	@Test
	void haveTheBeanNamesTheExperimentSettingsReferTo() {
		assertThat(EnglishFaqRetriever.BEAN_NAME).isEqualTo("englishFaqRetriever");
		assertThat(EnglishPlanRetriever.BEAN_NAME).isEqualTo("englishPlanRetriever");
	}

	private static FaqSimilarityResult faq(Long id, double similarity) {
		return new FaqSimilarityResult(id, "모바일", "요금제", "질문", "답변", similarity, null);
	}

	private static PlanSimilarityResult plan(String code, double similarity) {
		return new PlanSimilarityResult(1L, code, code, "요약", 30_000, "설명", similarity, null,
				"LTE_5G", "GENERAL", null, null, "LIMITED", 40960L, 1000, "UNLIMITED", null, "UNLIMITED", null);
	}
}
