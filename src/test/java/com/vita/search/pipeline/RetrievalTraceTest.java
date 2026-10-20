package com.vita.search.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqVectorSearchRepository;
import com.vita.search.service.PlanSearchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 검색 결과에 실린 기록용 정보({@link RetrievalTrace}): 질문 벡터, 가드 적용 여부, 적용한 설정이 실제 검색과 맞는지 확인한다. */
class RetrievalTraceTest {

	private final EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
	private final FaqVectorSearchRepository faqRepository = mock(FaqVectorSearchRepository.class);
	private final PlanSearchService planSearchService = mock(PlanSearchService.class);
	private final float[] vectorA = {0.1f};
	private final float[] vectorB = {0.2f};

	private RetrievalPipeline pipeline;

	@BeforeEach
	void setUp() {
		pipeline = new RetrievalPipeline(embeddingProvider, new IdentityQueryTransformer(), new VectorFaqRetriever(faqRepository),
				planSearchService, new RetrievalSettings(0.83, 0.79, true, 0.01, 3, 20));
		when(faqRepository.searchBySimilarity(any(float[].class), eq(FaqStatus.ACTIVE), anyDouble(), anyInt()))
				.thenReturn(List.of(new FaqSimilarityResult(1L, "해외로밍", "데이터 로밍", "질문", "답변", 0.9, null)));
		PlanSimilarityResult plan = new PlanSimilarityResult(1L, "VITA-MAX", "비타 맥스", "요약", 69000, "설명", 0.85, null,
				"5G", "GENERAL", null, null, "UNLIMITED", null, null, "UNLIMITED", null, "UNLIMITED", null);
		when(planSearchService.search(any(), any(), any(float[].class), anyInt(), anyInt()))
				.thenReturn(new PlanSearchService.PlanSearchOutcome(List.of(plan), false));
	}

	@Test
	void carriesTheQueryVectorsAndAppliedSettingsOfTheSearch() {
		when(embeddingProvider.embedQuery("FAQ용")).thenReturn(vectorA);
		when(embeddingProvider.embedQuery("요금제용")).thenReturn(vectorB);

		RetrievalResult result = pipeline.run("원문", "FAQ용", "요금제용", RetrievalOptions.forService(10));

		RetrievalTrace trace = result.trace();
		assertThat(trace.faqVector()).isSameAs(vectorA);
		assertThat(trace.planVector()).isSameAs(vectorB);
		assertThat(trace.faqSearched()).isTrue();
		assertThat(trace.planSearched()).isTrue();
		assertThat(trace.nonMobileGuard()).isFalse();
		assertThat(trace.settings().faqThreshold()).isEqualTo(0.83);
		assertThat(trace.options().topK()).isEqualTo(10);
	}

	@Test
	void sharesOneVectorObjectWhenBothQueriesAreTheSame() {
		when(embeddingProvider.embedQuery("같은 질문")).thenReturn(vectorA);

		RetrievalTrace trace = pipeline.run("원문", "같은 질문", "같은 질문", RetrievalOptions.forService(10)).trace();

		assertThat(trace.planVector()).isSameAs(trace.faqVector());
	}

	@Test
	void marksTheGuardAndLeavesThePlanVectorEmptyForANonMobileServiceQuestion() {
		when(embeddingProvider.embedQuery(any())).thenReturn(vectorA);

		RetrievalResult result = pipeline.run("인터넷 요금 알려줘", "인터넷 요금", "인터넷 요금", RetrievalOptions.forService(10));

		assertThat(result.trace().nonMobileGuard()).isTrue();
		assertThat(result.trace().planSearched()).isFalse();
		assertThat(result.trace().planVector()).isNull();
		assertThat(result.trace().faqSearched()).isTrue();
	}

	@Test
	void keepsATraceEvenWhenNothingIsSearched() {
		RetrievalResult result = pipeline.run("원문", null, null, RetrievalOptions.forService(10));

		assertThat(result.trace().faqSearched()).isFalse();
		assertThat(result.trace().planSearched()).isFalse();
		assertThat(result.trace().faqVector()).isNull();
	}

	@Test
	void keepsATraceWhenAnIrrelevantRuleClearsTheContext() {
		when(embeddingProvider.embedQuery(any())).thenReturn(vectorA);

		RetrievalResult result = pipeline.run("내 이번 달 요금 얼마 나왔어?", "요금 조회", "요금 조회", RetrievalOptions.forService(10));

		assertThat(result.irrelevantRules()).isNotEmpty();
		assertThat(result.context().references()).isEmpty();
		assertThat(result.trace()).isNotNull();
		assertThat(result.trace().faqVector()).isSameAs(vectorA);
	}
}
