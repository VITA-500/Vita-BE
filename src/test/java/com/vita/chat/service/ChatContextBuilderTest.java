package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vita.chat.dto.DataRange;
import com.vita.chat.dto.PlanIntent;
import com.vita.chat.dto.PriceRange;
import com.vita.search.dto.PlanReference;
import com.vita.search.service.PlanLookupService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 요금제 전체 목록 질문의 안내 문구가 일반 요금제 목록 앞에만, 그리고 요금제가 있을 때만 들어가는지 확인한다. */
class ChatContextBuilderTest {

	private final ChatContextBuilder builder = new ChatContextBuilder(mock(PlanLookupService.class));

	private static PlanReference plan(String name, int fee) {
		return new PlanReference(1L, name, name, "요약", fee, "설명", 0.8, null,
				"LTE_5G", "GENERAL", null, null, "LIMITED", 10240L, 1000, "UNLIMITED", null, "UNLIMITED", null);
	}

	private ChatContextBuilder.ChatContext build(List<PlanReference> plans, String planNotice) {
		return builder.build("전체 요금제 알려줘", List.of(), plans, PlanIntent.none(), PriceRange.none(), DataRange.none(), planNotice);
	}

	@Test
	void putsThePlanNoticeBeforeThePlansWhenGiven() {
		String text = build(List.of(plan("비타 라이트 5", 25_000)), "안내: 전체 목록임").text();

		assertThat(text).contains("<plan_notice>").contains("안내: 전체 목록임").contains("</plan_notice>");
		assertThat(text.indexOf("<plan_notice>")).isLessThan(text.indexOf("<plan>"));
	}

	@Test
	void leavesTheContextUnchangedWithoutANoticeOrWithoutPlans() {
		List<PlanReference> plans = List.of(plan("비타 라이트 5", 25_000));

		assertThat(build(plans, null).text()).doesNotContain("plan_notice");
		assertThat(build(plans, "  ").text()).doesNotContain("plan_notice");
		// 요금제가 하나도 없으면 안내만 남기지 않는다.
		assertThat(build(List.of(), "안내: 전체 목록임").text()).isEmpty();
	}

	private static PlanReference unlimited(String name, int fee) {
		return new PlanReference(1L, name, name, "요약", fee, "설명", 1.0, null,
				"LTE_5G", "GENERAL", null, null, "UNLIMITED", null, null, "UNLIMITED", null, "UNLIMITED", null);
	}

	@Test
	void keepsExtremePlansThatTieOnTheSortValueEvenWhenTheLimitIsOne() {
		PlanLookupService lookup = mock(PlanLookupService.class);
		org.mockito.Mockito.when(lookup.findExtremeForQuery(com.vita.search.service.PlanSortKey.MOST_DATA, 1, "데이터 제일 많이 주는 요금제?"))
				.thenReturn(List.of(unlimited("비타 맥스", 69_000), unlimited("비타 맥스 쉐어", 75_000)));
		ChatContextBuilder withLookup = new ChatContextBuilder(lookup);

		ChatContextBuilder.ChatContext context = withLookup.build("데이터 제일 많이 주는 요금제?", List.of(), List.of(),
				new PlanIntent(true, com.vita.search.service.PlanSortKey.MOST_DATA, 1), PriceRange.none(), DataRange.none());

		// 무제한이 둘이라 하나만 답하지 않고 둘 다 비교 결과에 넣는다.
		assertThat(context.plans()).extracting(PlanReference::name).containsExactly("비타 맥스", "비타 맥스 쉐어");
		assertThat(context.text()).contains("<comparison_result>").contains("비타 맥스 쉐어");
	}

	@Test
	void sixArgumentBuildKeepsWorkingAsBefore() {
		String text = builder.build("요금제 알려줘", List.of(), List.of(plan("비타 라이트 5", 25_000)),
				PlanIntent.none(), PriceRange.none(), DataRange.none()).text();

		assertThat(text).contains("<plan>").doesNotContain("plan_notice");
	}

	@Test
	void recordsTheExtremeLookupInputsAndHowTheResultWasNarrowed() {
		PlanLookupService lookup = mock(PlanLookupService.class);
		org.mockito.Mockito.when(lookup.findExtremeForQuery(com.vita.search.service.PlanSortKey.CHEAPEST, 50, "3만원대 중 가장 싼 요금제"))
				.thenReturn(List.of(plan("비타 라이트 5", 25_000), plan("비타 라이트 10", 31_000), plan("비타 라이트 20", 37_000)));
		ChatContextBuilder withLookup = new ChatContextBuilder(lookup);

		ChatContextBuilder.ChatContext context = withLookup.build("3만원대 중 가장 싼 요금제", List.of(), List.of(),
				new PlanIntent(true, com.vita.search.service.PlanSortKey.CHEAPEST, 1), new PriceRange(30_000L, 39_999L),
				DataRange.none());

		ChatContextBuilder.ExtremeLookup recorded = context.extremeLookup();
		assertThat(recorded.sortKey()).isEqualTo(com.vita.search.service.PlanSortKey.CHEAPEST);
		assertThat(recorded.limit()).isEqualTo(1);
		assertThat(recorded.scopeGroup()).isEqualTo("GENERAL");
		assertThat(recorded.fetched()).isEqualTo(3);
		assertThat(recorded.inRange()).isEqualTo(2);
		assertThat(recorded.returned()).extracting(PlanReference::name).containsExactly("비타 라이트 10");
		assertThat(recorded.noMatch()).isFalse();
		assertThat(recorded.nonMobileGuard()).isFalse();
		assertThat(recorded.priceRange().minWon()).isEqualTo(30_000L);
	}

	@Test
	void recordsNoMatchAndKeepsTheLookupWhenNothingFitsTheRange() {
		PlanLookupService lookup = mock(PlanLookupService.class);
		org.mockito.Mockito.when(lookup.findExtremeForQuery(com.vita.search.service.PlanSortKey.CHEAPEST, 50, "1만원 이하 중 가장 싼 요금제"))
				.thenReturn(List.of(plan("비타 라이트 5", 25_000)));
		ChatContextBuilder withLookup = new ChatContextBuilder(lookup);

		ChatContextBuilder.ChatContext context = withLookup.build("1만원 이하 중 가장 싼 요금제", List.of(), List.of(),
				new PlanIntent(true, com.vita.search.service.PlanSortKey.CHEAPEST, 1), new PriceRange(null, 10_000L),
				DataRange.none());

		assertThat(context.extremeLookup().noMatch()).isTrue();
		assertThat(context.extremeLookup().returned()).isEmpty();
		assertThat(context.extremeLookup().fetched()).isEqualTo(1);
	}

	@Test
	void marksTheNonMobileGuardForAnInternetPriceQuestionAndHasNoLookupForOrdinaryQuestions() {
		ChatContextBuilder.ChatContext internet = builder.build("가장 저렴한 인터넷 요금제", List.of(), List.of(),
				new PlanIntent(true, com.vita.search.service.PlanSortKey.CHEAPEST, 1), PriceRange.none(), DataRange.none());
		ChatContextBuilder.ChatContext ordinary = build(List.of(plan("비타 라이트 5", 25_000)), null);

		assertThat(internet.extremeLookup().nonMobileGuard()).isTrue();
		assertThat(ordinary.extremeLookup()).isNull();
	}
}
