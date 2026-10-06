package com.vita.search.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqVectorSearchRepository;
import com.vita.search.service.PlanSearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 검색 파이프라인의 기존 동작(무관 질문 규칙, 분류 가산점, threshold, 요금제 상세 필드 전달)이 파이프라인 분리 뒤에도 그대로인지 검증한다.
 * 서비스 구현을 파이프라인으로 옮기기 전의 FaqRetrievalServiceImplTest 케이스를 그대로 가져왔다.
 */
class RetrievalPipelineTest {

	private final EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
	private final FaqVectorSearchRepository faqRepository = mock(FaqVectorSearchRepository.class);
	private final PlanSearchService planSearchService = mock(PlanSearchService.class);

	private RetrievalPipeline pipeline;

	@BeforeEach
	void setUp() {
		pipeline = pipelineWith(0.0, true);
		when(embeddingProvider.embedQuery(any())).thenReturn(new float[] {0.1f});
	}

	/** 분류 이름 가산점과 무관 질문 규칙 사용 여부만 바꾼 기본 파이프라인(변환 없음 + 벡터 검색)을 만든다. */
	private RetrievalPipeline pipelineWith(double categoryBoostBonus, boolean irrelevantRuleEnabled) {
		return new RetrievalPipeline(embeddingProvider, new IdentityQueryTransformer(), new VectorFaqRetriever(faqRepository),
				planSearchService, new RetrievalSettings(0.83, 0.81, irrelevantRuleEnabled, categoryBoostBonus));
	}

	private void stubSearch(double faqSimilarity, double planSimilarity) {
		FaqSimilarityResult faq = new FaqSimilarityResult(
				1L, "요금 및 납부", "요금조회", "이번 달 통신요금 청구액을 확인하고 싶어요.", "마이페이지에서 확인할 수 있습니다.",
				faqSimilarity, null);
		when(faqRepository.searchBySimilarity(any(float[].class), eq(FaqStatus.ACTIVE), anyDouble(), anyInt()))
				.thenReturn(List.of(faq));

		PlanSimilarityResult plan = new PlanSimilarityResult(
				1L, "VITA-MAX", "비타 맥스", "무제한 요금제", 69000, "설명", planSimilarity, null,
					"5G", "GENERAL", null, null, "UNLIMITED", null, null, "UNLIMITED", null, "UNLIMITED", null);
		when(planSearchService.search(any(), any(float[].class), anyInt()))
				.thenReturn(new PlanSearchService.PlanSearchOutcome(List.of(plan), false));
	}

	private FaqRetrievalContext searchContext(String query, int topK) {
		return pipeline.run(query, topK).context();
	}

	@Test
	void clearsFaqAndPlanContextForPersonalLookupQuestionButKeepsRealTopSimilarity() {
		stubSearch(0.87, 0.85);

		FaqRetrievalContext context = searchContext("내 이번 달 요금 얼마 나왔어?", 3);

		assertThat(context.references()).isEmpty();
		assertThat(context.planReferences()).isEmpty();
		assertThat(context.topSimilarity()).isEqualTo(0.87);
	}

	@Test
	void clearsPlanContextForCompetitorQuestionToo() {
		stubSearch(0.80, 0.85);

		FaqRetrievalContext context = searchContext("SKT 요금제랑 비교하면 어때?", 3);

		assertThat(context.references()).isEmpty();
		assertThat(context.planReferences()).isEmpty();
		assertThat(context.topSimilarity()).isEqualTo(0.85);
	}

	@Test
	void keepsNormalQuestionResultsUnchanged() {
		stubSearch(0.87, 0.70);

		FaqRetrievalContext context = searchContext("자동이체 계좌를 변경하고 싶어요", 3);

		assertThat(context.references()).hasSize(1);
		assertThat(context.planReferences()).isEmpty();
		assertThat(context.topSimilarity()).isEqualTo(0.87);
	}

	private void stubFaqPool(FaqSimilarityResult... pool) {
		when(faqRepository.searchBySimilarity(any(float[].class), eq(FaqStatus.ACTIVE), anyDouble(), anyInt()))
				.thenReturn(List.of(pool));
		when(planSearchService.search(any(), any(float[].class), anyInt()))
				.thenReturn(new PlanSearchService.PlanSearchOutcome(List.of(), false));
	}

	private static FaqSimilarityResult faqOf(long id, String category, String subcategory, double similarity) {
		return new FaqSimilarityResult(id, category, subcategory, "질문" + id, "답변" + id, similarity, null);
	}

	@Test
	void promotesTheCategoryNamedInTheQueryButKeepsTheOriginalTopSimilarity() {
		pipeline = pipelineWith(0.01, true);
		stubFaqPool(
				faqOf(1, "인터넷/IPTV", "IPTV 상품안내", 0.866),
				faqOf(2, "소상공인", "IPTV", 0.863));

		FaqRetrievalContext context = searchContext("IPTV 설치가 안 되는 상가 지역도 있나요?", 3);

		// 질문의 "IPTV", "상가"에 맞는 소상공인/IPTV가 1등이 된다.
		assertThat(context.references()).extracting(r -> r.subcategory()).containsExactly("IPTV", "IPTV 상품안내");
		// BE4에 전달하는 최고 유사도는 순위와 상관없이 원래 최고값이다.
		assertThat(context.topSimilarity()).isEqualTo(0.866);
		// 각 후보의 유사도 값도 바뀌지 않는다.
		assertThat(context.references()).extracting(r -> r.similarity()).containsExactly(0.863, 0.866);
	}

	@Test
	void keepsOriginalOrderWhenCategoryBoostIsDisabled() {
		pipeline = pipelineWith(0.0, true);
		stubFaqPool(
				faqOf(1, "인터넷/IPTV", "IPTV 상품안내", 0.866),
				faqOf(2, "소상공인", "IPTV", 0.863));

		FaqRetrievalContext context = searchContext("IPTV 설치가 안 되는 상가 지역도 있나요?", 3);

		assertThat(context.references()).extracting(r -> r.subcategory()).containsExactly("IPTV 상품안내", "IPTV");
	}

	@Test
	void stillAppliesTheSimilarityThresholdToTheOriginalSimilarityAfterReranking() {
		pipeline = pipelineWith(0.01, true);
		stubFaqPool(
				faqOf(1, "인터넷/IPTV", "IPTV 상품안내", 0.832),
				faqOf(2, "소상공인", "IPTV", 0.825));

		FaqRetrievalContext context = searchContext("소상공인 IPTV 설치", 3);

		// 가산점으로 앞섰더라도 원래 유사도 0.825는 threshold(0.83) 미만이라 제외된다.
		assertThat(context.references()).extracting(r -> r.subcategory()).containsExactly("IPTV 상품안내");
	}

	@Test
	void passesPlanDetailFieldsToPlanReferenceKeepingNulls() {
		PlanSimilarityResult limited = new PlanSimilarityResult(
				4L, "VITA-BALANCE-40", "비타 밸런스 40", "요약", 43000, "설명", 0.86, null,
				"LTE_5G", "GENERAL", null, null, "LIMITED", 40960L, 1000, "UNLIMITED", null, "UNLIMITED", null);
		PlanSimilarityResult youth = new PlanSimilarityResult(
				5L, "VITA-YOUTH", "비타 유스", "요약", 33000, "설명", 0.85, null,
				"5G", "YOUTH", 19, 34, "LIMITED", 20480L, null, "LIMITED", 300, "LIMITED", 100);
		when(faqRepository.searchBySimilarity(any(float[].class), eq(FaqStatus.ACTIVE), anyDouble(), anyInt()))
				.thenReturn(List.of());
		when(planSearchService.search(any(), any(float[].class), anyInt()))
				.thenReturn(new PlanSearchService.PlanSearchOutcome(List.of(limited, youth), false));

		var plans = searchContext("요금제 추천해줘", 3).planReferences();

		var first = plans.get(0);
		assertThat(first.networkType()).isEqualTo("LTE_5G");
		assertThat(first.targetGroup()).isEqualTo("GENERAL");
		assertThat(first.minAge()).isNull();
		assertThat(first.baseDataMb()).isEqualTo(40960L);
		assertThat(first.exhaustedSpeedKbps()).isEqualTo(1000);
		assertThat(first.voicePolicy()).isEqualTo("UNLIMITED");
		assertThat(first.voiceMinutes()).isNull();
		assertThat(first.smsCount()).isNull();

		var second = plans.get(1);
		assertThat(second.targetGroup()).isEqualTo("YOUTH");
		assertThat(second.minAge()).isEqualTo(19);
		assertThat(second.maxAge()).isEqualTo(34);
		assertThat(second.exhaustedSpeedKbps()).isNull();
		assertThat(second.voiceMinutes()).isEqualTo(300);
		assertThat(second.smsCount()).isEqualTo(100);
	}

	@Test
	void keepsFirstPersonHowToQuestionResults() {
		stubSearch(0.87, 0.70);

		FaqRetrievalContext context = searchContext("제 요금제를 다른 요금제로 바꾸고 싶어요", 3);

		assertThat(context.references()).hasSize(1);
	}

	@Test
	void doesNotClearContextWhenRuleIsDisabled() {
		pipeline = pipelineWith(0.0, false);
		stubSearch(0.87, 0.85);

		FaqRetrievalContext context = searchContext("내 이번 달 요금 얼마 나왔어?", 3);

		assertThat(context.references()).hasSize(1);
		assertThat(context.planReferences()).hasSize(1);
	}

	// ---- 파이프라인 분리로 새로 생긴 동작 ----

	/** 질문 변환 결과(FAQ용/요금제용 질문)를 지정한 대로 돌려주는 테스트용 변환기. */
	private static QueryTransformer transformerTo(String faqQuery, String planQuery) {
		return query -> new TransformedQuery(query, faqQuery, planQuery);
	}

	@Test
	void usesTheTransformedQueriesForEmbeddingAndSearchButTheOriginalForRulesAndTheCategoryBooster() {
		FaqRetriever retriever = mock(FaqRetriever.class);
		when(retriever.retrieve(any(), anyInt())).thenReturn(List.of(
				faqOf(1, "인터넷/IPTV", "IPTV 상품안내", 0.866),
				faqOf(2, "소상공인", "IPTV", 0.863)));
		when(embeddingProvider.embedQuery("english faq query")).thenReturn(new float[] {0.2f});
		when(embeddingProvider.embedQuery("플랜 질문")).thenReturn(new float[] {0.3f});
		when(planSearchService.search(any(), any(float[].class), anyInt()))
				.thenReturn(new PlanSearchService.PlanSearchOutcome(List.of(), false));
		pipeline = new RetrievalPipeline(embeddingProvider, transformerTo("english faq query", "플랜 질문"), retriever,
				planSearchService, new RetrievalSettings(0.83, 0.81, true, 0.01));

		// 원문의 "IPTV", "상가"가 분류 이름 가산점에 쓰이므로 소상공인/IPTV가 1등이 된다(변환된 영어 질문에는 분류 이름이 없다).
		RetrievalResult result = pipeline.run("IPTV 설치가 안 되는 상가 지역도 있나요?", 3);

		ArgumentCaptor<RetrievalQuery> queryCaptor = ArgumentCaptor.forClass(RetrievalQuery.class);
		verify(retriever).retrieve(queryCaptor.capture(), eq(30));
		assertThat(queryCaptor.getValue().text()).isEqualTo("english faq query");
		assertThat(queryCaptor.getValue().vector()).containsExactly(0.2f);
		verify(planSearchService).search(eq("플랜 질문"), eq(new float[] {0.3f}), eq(3));
		assertThat(result.query().original()).isEqualTo("IPTV 설치가 안 되는 상가 지역도 있나요?");
		assertThat(result.context().references()).extracting(r -> r.subcategory()).containsExactly("IPTV", "IPTV 상품안내");
	}

	@Test
	void embedsOnlyOnceWhenTheFaqAndPlanQueriesAreTheSame() {
		stubFaqPool(faqOf(1, "요금 및 납부", "요금조회", 0.9));

		pipeline.run("자동이체 계좌를 변경하고 싶어요", 3);

		verify(embeddingProvider, times(1)).embedQuery(any());
	}

	@Test
	void keepsThePoolAtThirtyCandidatesEvenWhenTopKGrowsToTen() {
		FaqRetriever retriever = mock(FaqRetriever.class);
		when(retriever.retrieve(any(), anyInt())).thenReturn(List.of());
		when(planSearchService.search(any(), any(float[].class), anyInt()))
				.thenReturn(new PlanSearchService.PlanSearchOutcome(List.of(), false));
		pipeline = new RetrievalPipeline(embeddingProvider, new IdentityQueryTransformer(), retriever, planSearchService,
				new RetrievalSettings(0.83, 0.81, true, 0.01));

		pipeline.run("로밍 신청 방법", 3);
		pipeline.run("로밍 신청 방법", 10);

		verify(retriever, times(2)).retrieve(any(), eq(30));
	}

	@Test
	void skipsThePlanSearchWhenTheOptionsSayFaqOnly() {
		stubFaqPool(faqOf(1, "요금 및 납부", "요금조회", 0.9));

		RetrievalResult result = pipeline.run("자동이체 계좌를 변경하고 싶어요", RetrievalOptions.forEval(3, 30, false));

		verify(planSearchService, never()).search(any(), any(float[].class), anyInt());
		assertThat(result.planResults()).isEmpty();
		assertThat(result.timings().planSearchNanos()).isZero();
		assertThat(result.faq().results()).hasSize(1);
	}

	@Test
	void reportsStageTimingsThatAddUpToTheTotal() {
		stubFaqPool(faqOf(1, "요금 및 납부", "요금조회", 0.9));

		StageTimings timings = pipeline.run("자동이체 계좌를 변경하고 싶어요", 3).timings();

		long sum = 0;
		for (StageTimings.Stage stage : StageTimings.Stage.values()) {
			assertThat(timings.nanosOf(stage)).as(stage.label()).isNotNegative();
			sum += timings.nanosOf(stage);
		}
		assertThat(timings.totalNanos()).isEqualTo(sum);
		assertThat(timings.summary()).contains("임베딩", "FAQ 검색", "요금제 검색", "Context 구성", "전체");
	}

	@Test
	void selectFaqAppliesBoostDedupeTopKAndThresholdInOrderAndKeepsThePoolsTopSimilarity() {
		FaqSimilarityResult sameAnswerOriginal = new FaqSimilarityResult(1L, "로밍", "신청", "로밍 신청 방법", "같은 답변", 0.90, null);
		FaqSimilarityResult sameAnswerVariant = new FaqSimilarityResult(2L, "로밍", "신청", "로밍 신청은 어떻게 하나요", "같은 답변", 0.89, null);
		FaqSimilarityResult other = new FaqSimilarityResult(3L, "로밍", "요금", "로밍 요금", "다른 답변", 0.84, null);
		FaqSimilarityResult belowThreshold = new FaqSimilarityResult(4L, "로밍", "기타", "기타", "또 다른 답변", 0.80, null);

		FaqSelection selection = pipeline.selectFaq("로밍 신청",
				List.of(sameAnswerOriginal, sameAnswerVariant, other, belowThreshold), 3);

		// 같은 답변은 하나로 합쳐지고(원문 유지), topK 3개 후보 중 threshold(0.83) 미만은 결과에서 빠진다.
		assertThat(selection.candidates()).extracting(r -> r.id()).containsExactly(1L, 3L, 4L);
		assertThat(selection.results()).extracting(r -> r.id()).containsExactly(1L, 3L);
		assertThat(selection.topSimilarity()).isEqualTo(0.90);
		assertThat(selection.pool()).hasSize(4);
	}

	@Test
	void pickingAnUnknownBeanNameFailsWithTheAvailableNames() {
		assertThatThrownBy(() -> RetrievalPipelineConfig.pick(
				Map.of("identityQueryTransformer", new IdentityQueryTransformer()), "englishTransformer", "search.pipeline.query-transformer"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("englishTransformer")
				.hasMessageContaining("identityQueryTransformer");
	}
}
