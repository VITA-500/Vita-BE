package com.vita.search.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.repository.PlanVectorSearchRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class VectorPlanRetrieverTest {

	private final PlanVectorSearchRepository repository = mock(PlanVectorSearchRepository.class);
	private final VectorPlanRetriever retriever = new VectorPlanRetriever(repository);

	@Test
	void searchesTheVectorRepositoryWithoutAThresholdAndKeepsItsOrder() {
		float[] vector = {0.1f, 0.2f};
		List<PlanSimilarityResult> found = List.of(plan("VITA-MAX", 0.90), plan("VITA-LITE-5", 0.82));
		when(repository.searchBySimilarity(vector, 0.0, 50)).thenReturn(found);

		List<PlanSimilarityResult> result = retriever.retrieve(new RetrievalQuery("요금제 질문", vector), 50);

		// threshold는 여기서 걸지 않고(0.0), 리포지토리가 돌려준 순서와 유사도를 그대로 전달한다.
		assertThat(result).isSameAs(found);
		verify(repository).searchBySimilarity(vector, 0.0, 50);
	}

	@Test
	void hasTheDefaultBeanNameTheConfigurationFallsBackTo() {
		assertThat(VectorPlanRetriever.BEAN_NAME).isEqualTo("vectorPlanRetriever");
	}

	private static PlanSimilarityResult plan(String code, double similarity) {
		return new PlanSimilarityResult(1L, code, code, "요약", 30_000, "설명", similarity, null,
				"LTE_5G", "GENERAL", null, null, "LIMITED", 40960L, 1000, "UNLIMITED", null, "UNLIMITED", null);
	}
}
