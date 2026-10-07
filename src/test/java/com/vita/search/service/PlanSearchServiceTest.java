package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.repository.PlanLookupRepository;
import com.vita.search.repository.PlanVectorSearchRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 기기 전용 요금제 기본 제외와 범위(가격) 질문의 전체 결과 반환·가격순 정렬을 검증한다. */
class PlanSearchServiceTest {

	private static final int TOP_K = 3;

	private final PlanVectorSearchRepository vectorRepository = mock(PlanVectorSearchRepository.class);
	private final PlanLookupRepository lookupRepository = mock(PlanLookupRepository.class);
	private PlanSearchService service;

	@BeforeEach
	void setUp() {
		service = new PlanSearchService(vectorRepository, lookupRepository);
		when(lookupRepository.findTargetGroupByPlanCode()).thenReturn(Map.of(
				"VITA-WATCH-1", "WATCH",
				"VITA-TABLET-20", "TABLET",
				"VITA-LITE-5", "GENERAL",
				"VITA-LITE-10", "GENERAL"));
	}

	private static PlanSimilarityResult plan(String code, int fee, double similarity) {
		return new PlanSimilarityResult(1L, code, code, "요약", fee, "설명", similarity, null,
				"LTE_5G", "GENERAL", null, null, "LIMITED", 40960L, 1000, "UNLIMITED", null, "UNLIMITED", null);
	}

	private void stubPool(PlanSimilarityResult... plans) {
		when(vectorRepository.searchBySimilarity(any(float[].class), anyDouble(), anyInt())).thenReturn(List.of(plans));
	}

	private List<String> codes(PlanSearchService.PlanSearchOutcome outcome) {
		return outcome.results().stream().map(PlanSimilarityResult::planCode).toList();
	}

	@Test
	void excludesDeviceOnlyPlansFromVectorResultsWhenNoDeviceIsMentioned() {
		stubPool(plan("VITA-WATCH-1", 11_000, 0.90), plan("VITA-LITE-10", 31_000, 0.85),
				plan("VITA-TABLET-20", 22_000, 0.84), plan("VITA-LITE-5", 25_000, 0.83));

		PlanSearchService.PlanSearchOutcome outcome = service.search("요금제 추천해줘", new float[] {0.1f}, TOP_K);

		assertThat(codes(outcome)).containsExactly("VITA-LITE-10", "VITA-LITE-5");
		assertThat(outcome.conditionMatched()).isFalse();
	}

	@Test
	void keepsDeviceOnlyPlansWhenTheQuestionMentionsTheDevice() {
		stubPool(plan("VITA-WATCH-1", 11_000, 0.90), plan("VITA-LITE-10", 31_000, 0.85));

		PlanSearchService.PlanSearchOutcome outcome = service.search("스마트워치 데이터 얼마나 줘?", new float[] {0.1f}, TOP_K);

		assertThat(codes(outcome)).containsExactly("VITA-WATCH-1", "VITA-LITE-10");
	}

	@Test
	void returnsAllMatchedPlansSortedByFeeEvenWhenMoreThanTopK() {
		stubPool(plan("VITA-SENIOR-20", 33_000, 0.80), plan("VITA-LITE-10", 31_000, 0.70),
				plan("VITA-BALANCE-20", 37_000, 0.90), plan("VITA-YOUTH-30", 35_000, 0.85),
				plan("VITA-PLUS-80", 51_000, 0.95));
		when(lookupRepository.findPlanCodesByConditions(any())).thenReturn(
				Set.of("VITA-LITE-10", "VITA-SENIOR-20", "VITA-YOUTH-30", "VITA-BALANCE-20"));

		PlanSearchService.PlanSearchOutcome outcome = service.search("3만원대 요금제 알려줘", new float[] {0.1f}, TOP_K);

		assertThat(codes(outcome)).containsExactly(
				"VITA-LITE-10", "VITA-SENIOR-20", "VITA-YOUTH-30", "VITA-BALANCE-20");
		assertThat(outcome.conditionMatched()).isTrue();
	}

	@Test
	void keepsSimilarityOrderWhenThereIsNoFeeCondition() {
		stubPool(plan("VITA-YOUTH-30", 35_000, 0.80), plan("VITA-YOUTH-70", 45_000, 0.90));
		when(lookupRepository.findPlanCodesByConditions(any())).thenReturn(Set.of("VITA-YOUTH-30", "VITA-YOUTH-70"));

		PlanSearchService.PlanSearchOutcome outcome = service.search("비타 유스 70 설명해줘", new float[] {0.1f}, TOP_K);

		// 이름을 지목한 질문의 1등은 더 싼 요금제가 아니라 질문과 가장 비슷한 요금제여야 한다.
		assertThat(codes(outcome)).containsExactly("VITA-YOUTH-70", "VITA-YOUTH-30");
	}

	private void stubTwelveMatchedPlans() {
		PlanSimilarityResult[] pool = new PlanSimilarityResult[12];
		Set<String> matched = new java.util.HashSet<>();
		for (int i = 0; i < 12; i++) {
			String code = "VITA-PLAN-" + i;
			pool[i] = plan(code, 10_000 + i * 1_000, 0.80);
			matched.add(code);
		}
		stubPool(pool);
		when(lookupRepository.findPlanCodesByConditions(any())).thenReturn(matched);
	}

	@Test
	void returnsEveryMatchedPlanUnderTheDefaultLimitOfTwenty() {
		stubTwelveMatchedPlans();

		PlanSearchService.PlanSearchOutcome outcome = service.search("무제한 아니고 적당한 요금제 있어?", new float[] {0.1f}, TOP_K);

		assertThat(outcome.results()).hasSize(12);
	}

	@Test
	void capsMatchedResultsAtTheConfiguredLimit() {
		stubTwelveMatchedPlans();

		PlanSearchService.PlanSearchOutcome outcome = service.search("무제한 아니고 적당한 요금제 있어?",
				"무제한 아니고 적당한 요금제 있어?", new float[] {0.1f}, TOP_K, 5);

		assertThat(outcome.results()).hasSize(5);
	}

	@Test
	void returnsOnlyTopKPlansWhenNoConditionMatches() {
		stubPool(plan("VITA-LITE-10", 31_000, 0.90), plan("VITA-LITE-5", 25_000, 0.85), plan("VITA-PLAN-3", 40_000, 0.84),
				plan("VITA-PLAN-4", 41_000, 0.83), plan("VITA-PLAN-5", 42_000, 0.82));

		PlanSearchService.PlanSearchOutcome outcome = service.search("요금제 추천해줘", "요금제 추천해줘", new float[] {0.1f}, 2, 20);

		assertThat(codes(outcome)).containsExactly("VITA-LITE-10", "VITA-LITE-5");
	}

	// ---- 원문과 요금제용 질문 양쪽에서 조건 읽기 ----

	private PlanQueryConditions searchAndCaptureConditions(String original, String planQuery) {
		stubPool(plan("VITA-LITE-10", 31_000, 0.90), plan("VITA-LITE-5", 25_000, 0.85));
		when(lookupRepository.findPlanCodesByConditions(any())).thenReturn(Set.of("VITA-LITE-10"));
		service.search(original, planQuery, new float[] {0.1f}, TOP_K, 20);
		org.mockito.ArgumentCaptor<PlanQueryConditions> captor = org.mockito.ArgumentCaptor.forClass(PlanQueryConditions.class);
		org.mockito.Mockito.verify(lookupRepository).findPlanCodesByConditions(captor.capture());
		return captor.getValue();
	}

	@Test
	void readsConditionsFromThePlanQueryWhenTheOriginalHasNoPlanWord() {
		// 원문에는 "요금제"라는 말이 없어 조건 추출기가 반응하지 않지만, 변환된 요금제용 질문에는 있다.
		PlanQueryConditions conditions = searchAndCaptureConditions("3만원대 뭐 있어?", "금액: 3만원대\n핵심 키워드: 요금제");

		assertThat(conditions.feeMin()).isEqualTo(30_000);
		assertThat(conditions.feeMax()).isEqualTo(39_999);
	}

	@Test
	void prefersTheOriginalsFeeConditionOverThePlanQuerys() {
		PlanQueryConditions conditions = searchAndCaptureConditions("5만원 이하 요금제 알려줘", "금액: 3만원대\n핵심 키워드: 요금제");

		assertThat(conditions.feeMax()).isEqualTo(50_000);
		assertThat(conditions.feeMin()).isNull(); // 원문의 상한과 변환 질문의 하한이 섞이지 않는다
	}

	@Test
	void fillsTheConditionsTheOriginalLacksFromThePlanQuery() {
		PlanQueryConditions conditions = searchAndCaptureConditions("청년 요금제 알려줘",
				"금액: 5만원 이하\n대상: 청년\n핵심 키워드: 청년 요금제");

		assertThat(conditions.targetGroup()).isEqualTo("YOUTH");
		assertThat(conditions.feeMax()).isEqualTo(50_000);
	}

	@Test
	void keepsDeviceOnlyPlansWhenOnlyThePlanQueryMentionsTheDevice() {
		stubPool(plan("VITA-WATCH-1", 11_000, 0.90), plan("VITA-LITE-10", 31_000, 0.85));

		PlanSearchService.PlanSearchOutcome outcome = service.search("요금제 추천해줘",
				"대상: 워치\n핵심 키워드: 워치 요금제", new float[] {0.1f}, TOP_K, 20);

		assertThat(codes(outcome)).containsExactly("VITA-WATCH-1", "VITA-LITE-10");
	}

	@Test
	void dropsDeviceOnlyPlansFromMatchedResultsWhenOtherPlansRemain() {
		stubPool(plan("VITA-LITE-10", 31_000, 0.80), plan("VITA-TABLET-20", 22_000, 0.90),
				plan("VITA-WATCH-1", 11_000, 0.85));
		when(lookupRepository.findPlanCodesByConditions(any())).thenReturn(
				Set.of("VITA-LITE-10", "VITA-TABLET-20", "VITA-WATCH-1"));

		PlanSearchService.PlanSearchOutcome outcome = service.search("무제한 아니고 적당히 쓰는 요금제 있어?", new float[] {0.1f}, TOP_K);

		assertThat(codes(outcome)).containsExactly("VITA-LITE-10");
	}

	@Test
	void keepsDeviceOnlyPlanWhenItIsTheOnlyMatchForThePrice() {
		stubPool(plan("VITA-WATCH-1", 11_000, 0.80), plan("VITA-LITE-10", 31_000, 0.85));
		when(lookupRepository.findPlanCodesByConditions(any())).thenReturn(Set.of("VITA-WATCH-1"));

		PlanSearchService.PlanSearchOutcome outcome = service.search("1만1천원짜리 요금제 있어?", new float[] {0.1f}, TOP_K);

		assertThat(codes(outcome)).containsExactly("VITA-WATCH-1");
		assertThat(outcome.conditionMatched()).isTrue();
	}
}
