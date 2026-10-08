package com.vita.search.hybrid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.pipeline.RetrievalQuery;
import com.vita.search.repository.FaqVectorSearchRepository;
import com.vita.search.repository.PlanVectorSearchRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class HybridRetrieversTest {

	private final HybridProperties properties = new HybridProperties(true, false, null, null);
	private final float[] vector = {0.1f, 0.2f};

	private final FaqHybridSearchRepository faqHybrid = mock(FaqHybridSearchRepository.class);
	private final FaqVectorSearchRepository faqVector = mock(FaqVectorSearchRepository.class);
	private final HybridFaqRetriever faqRetriever = new HybridFaqRetriever(faqHybrid, faqVector, properties);

	private final PlanHybridSearchRepository planHybrid = mock(PlanHybridSearchRepository.class);
	private final PlanVectorSearchRepository planVector = mock(PlanVectorSearchRepository.class);
	private final HybridPlanRetriever planRetriever = new HybridPlanRetriever(planHybrid, planVector, properties);

	@Test
	void faqRetrieverSearchesWithLabelFreeTextTheFaqWeightsAndTheCandidateLimit() {
		List<FaqSimilarityResult> found = List.of(new FaqSimilarityResult(1L, "c", "s", "q", "a", 0.9, null));
		when(faqHybrid.search(any(), anyString(), any(), anyInt(), anyInt())).thenReturn(found);

		List<FaqSimilarityResult> result = faqRetriever.retrieve(
				new RetrievalQuery("조건: 해외 출국\n질문: 해외에서 데이터를 쓰려면?\n핵심 키워드: 해외, 데이터, 로밍", vector), 30);

		assertThat(result).isSameAs(found);
		verify(faqHybrid).search(vector, "해외 출국 해외에서 데이터를 쓰려면? 해외, 데이터, 로밍", new HybridWeights(2.0, 1.0, 60), 100, 30);
		verify(faqVector, never()).searchBySimilarity(any(), any(), org.mockito.ArgumentMatchers.anyDouble(), anyInt());
	}

	@Test
	void planRetrieverSearchesWithThePlanWeightsAndAtLeastThePoolSizeAsCandidates() {
		List<PlanSimilarityResult> found = List.of();
		when(planHybrid.search(any(), anyString(), any(), anyInt(), anyInt())).thenReturn(found);

		planRetriever.retrieve(new RetrievalQuery("금액: 3만원대\n핵심 키워드: 3만원대 요금제", vector), 80);

		// 후보 상한(기본 50)이 풀 크기(80)보다 작으면 풀 크기를 쓴다.
		verify(planHybrid).search(vector, "3만원대 3만원대 요금제", new HybridWeights(1.0, 2.0, 60), 80, 80);
	}

	@Test
	void fallsBackToTheVectorSearchWhenThereIsNoKeywordText() {
		List<FaqSimilarityResult> vectorResult = List.of(new FaqSimilarityResult(2L, "c", "s", "q", "a", 0.8, null));
		when(faqVector.searchBySimilarity(vector, FaqStatus.ACTIVE, 0.0, 30)).thenReturn(vectorResult);
		List<PlanSimilarityResult> planVectorResult = List.of();
		when(planVector.searchBySimilarity(vector, 0.0, 50)).thenReturn(planVectorResult);

		assertThat(faqRetriever.retrieve(new RetrievalQuery("  ", vector), 30)).isSameAs(vectorResult);
		assertThat(planRetriever.retrieve(new RetrievalQuery("", vector), 50)).isSameAs(planVectorResult);
		verify(faqHybrid, never()).search(any(), any(), any(), anyInt(), anyInt());
		verify(planHybrid, never()).search(any(), any(), any(), anyInt(), eq(50));
	}

	@Test
	void hasTheBeanNamesTheConfigurationRefersTo() {
		assertThat(HybridFaqRetriever.BEAN_NAME).isEqualTo("hybridFaqRetriever");
		assertThat(HybridPlanRetriever.BEAN_NAME).isEqualTo("hybridPlanRetriever");
	}
}
