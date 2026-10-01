package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqVectorSearchRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 무관 질문 규칙 판정이 FAQ·요금제 컨텍스트를 비우는지(그리고 정상 질문은 그대로인지) 검증한다. */
class FaqRetrievalServiceImplTest {

	private final EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
	private final FaqVectorSearchRepository faqRepository = mock(FaqVectorSearchRepository.class);
	private final PlanSearchService planSearchService = mock(PlanSearchService.class);

	private FaqRetrievalServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new FaqRetrievalServiceImpl(embeddingProvider, faqRepository, planSearchService);
		ReflectionTestUtils.setField(service, "similarityThreshold", 0.83);
		ReflectionTestUtils.setField(service, "planSimilarityThreshold", 0.81);
		ReflectionTestUtils.setField(service, "irrelevantRuleEnabled", true);
		when(embeddingProvider.embedQuery(any())).thenReturn(new float[] {0.1f});
	}

	private void stubSearch(double faqSimilarity, double planSimilarity) {
		FaqSimilarityResult faq = new FaqSimilarityResult(
				1L, "요금 및 납부", "요금조회", "이번 달 통신요금 청구액을 확인하고 싶어요.", "마이페이지에서 확인할 수 있습니다.",
				faqSimilarity, null);
		when(faqRepository.searchBySimilarity(any(float[].class), eq(FaqStatus.ACTIVE), anyDouble(), anyInt()))
				.thenReturn(List.of(faq));

		PlanSimilarityResult plan = new PlanSimilarityResult(
				1L, "VITA-MAX", "비타 맥스", "무제한 요금제", 69000, "설명", planSimilarity, null);
		when(planSearchService.search(any(), any(float[].class), anyInt()))
				.thenReturn(new PlanSearchService.PlanSearchOutcome(List.of(plan), false));
	}

	@Test
	void clearsFaqAndPlanContextForPersonalLookupQuestionButKeepsRealTopSimilarity() {
		stubSearch(0.87, 0.85);

		FaqRetrievalContext context = service.search("내 이번 달 요금 얼마 나왔어?", 3);

		assertThat(context.references()).isEmpty();
		assertThat(context.planReferences()).isEmpty();
		assertThat(context.topSimilarity()).isEqualTo(0.87);
	}

	@Test
	void clearsPlanContextForCompetitorQuestionToo() {
		stubSearch(0.80, 0.85);

		FaqRetrievalContext context = service.search("SKT 요금제랑 비교하면 어때?", 3);

		assertThat(context.references()).isEmpty();
		assertThat(context.planReferences()).isEmpty();
		assertThat(context.topSimilarity()).isEqualTo(0.85);
	}

	@Test
	void keepsNormalQuestionResultsUnchanged() {
		stubSearch(0.87, 0.70);

		FaqRetrievalContext context = service.search("자동이체 계좌를 변경하고 싶어요", 3);

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
		ReflectionTestUtils.setField(service, "categoryBoostBonus", 0.01);
		stubFaqPool(
				faqOf(1, "인터넷/IPTV", "IPTV 상품안내", 0.866),
				faqOf(2, "소상공인", "IPTV", 0.863));

		FaqRetrievalContext context = service.search("IPTV 설치가 안 되는 상가 지역도 있나요?", 3);

		// 질문의 "IPTV", "상가"에 맞는 소상공인/IPTV가 1등이 된다.
		assertThat(context.references()).extracting(r -> r.subcategory()).containsExactly("IPTV", "IPTV 상품안내");
		// BE4에 전달하는 최고 유사도는 순위와 상관없이 원래 최고값이다.
		assertThat(context.topSimilarity()).isEqualTo(0.866);
		// 각 후보의 유사도 값도 바뀌지 않는다.
		assertThat(context.references()).extracting(r -> r.similarity()).containsExactly(0.863, 0.866);
	}

	@Test
	void keepsOriginalOrderWhenCategoryBoostIsDisabled() {
		ReflectionTestUtils.setField(service, "categoryBoostBonus", 0.0);
		stubFaqPool(
				faqOf(1, "인터넷/IPTV", "IPTV 상품안내", 0.866),
				faqOf(2, "소상공인", "IPTV", 0.863));

		FaqRetrievalContext context = service.search("IPTV 설치가 안 되는 상가 지역도 있나요?", 3);

		assertThat(context.references()).extracting(r -> r.subcategory()).containsExactly("IPTV 상품안내", "IPTV");
	}

	@Test
	void stillAppliesTheSimilarityThresholdToTheOriginalSimilarityAfterReranking() {
		ReflectionTestUtils.setField(service, "categoryBoostBonus", 0.01);
		stubFaqPool(
				faqOf(1, "인터넷/IPTV", "IPTV 상품안내", 0.832),
				faqOf(2, "소상공인", "IPTV", 0.825));

		FaqRetrievalContext context = service.search("소상공인 IPTV 설치", 3);

		// 가산점으로 앞섰더라도 원래 유사도 0.825는 threshold(0.83) 미만이라 제외된다.
		assertThat(context.references()).extracting(r -> r.subcategory()).containsExactly("IPTV 상품안내");
	}

	@Test
	void keepsFirstPersonHowToQuestionResults() {
		stubSearch(0.87, 0.70);

		FaqRetrievalContext context = service.search("제 요금제를 다른 요금제로 바꾸고 싶어요", 3);

		assertThat(context.references()).hasSize(1);
	}

	@Test
	void doesNotClearContextWhenRuleIsDisabled() {
		ReflectionTestUtils.setField(service, "irrelevantRuleEnabled", false);
		stubSearch(0.87, 0.85);

		FaqRetrievalContext context = service.search("내 이번 달 요금 얼마 나왔어?", 3);

		assertThat(context.references()).hasSize(1);
		assertThat(context.planReferences()).hasSize(1);
	}
}
