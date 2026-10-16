package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vita.search.dto.PlanReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** findExtremeForQuery가 질문에서 읽은 대상 그룹으로 조회 범위를 정하는지 검증한다. */
class PlanLookupServiceTest {

	/** 호출된 조회 범위만 기록하는 가짜 구현. */
	private static final class RecordingLookup implements PlanLookupService {
		final List<String> groups = new ArrayList<>();

		@Override
		public List<PlanReference> findExtreme(PlanSortKey sortKey, int limit, String targetGroup) {
			groups.add(targetGroup);
			return List.of();
		}
	}

	private static String groupUsedFor(String query) {
		RecordingLookup lookup = new RecordingLookup();
		lookup.findExtremeForQuery(PlanSortKey.CHEAPEST, 1, query);
		return lookup.groups.get(0);
	}

	@Test
	void narrowsToTheGroupNamedInTheQuestion() {
		assertThat(groupUsedFor("워치 요금제 중에 제일 싼 거 알려줘")).isEqualTo("WATCH");
		assertThat(groupUsedFor("태블릿 요금제 중 데이터 가장 많이 주는 건?")).isEqualTo("TABLET");
		assertThat(groupUsedFor("청년 요금제 중에 가장 저렴한 거 알려줘")).isEqualTo("YOUTH");
		assertThat(groupUsedFor("시니어 요금제 중 제일 싼 요금제가 뭐예요?")).isEqualTo("SENIOR");
	}

	@Test
	void fallsBackToGeneralWhenNoSingleGroupIsNamed() {
		assertThat(groupUsedFor("가장 저렴한 요금제 알려줘")).isEqualTo("GENERAL");
		assertThat(groupUsedFor("청년이랑 시니어 중 제일 싼 요금제")).isEqualTo("GENERAL");
		assertThat(groupUsedFor(null)).isEqualTo("GENERAL");
	}

	@Test
	void returnsNothingForQuestionsAboutNonMobileServiceFees() {
		RecordingLookup lookup = new RecordingLookup();
		assertThat(lookup.findExtremeForQuery(PlanSortKey.CHEAPEST, 1, "가장 저렴한 인터넷 요금제 알려줘")).isEmpty();
		assertThat(lookup.findExtremeForQuery(PlanSortKey.CHEAPEST, 1, "제일 싼 IPTV 요금이 얼마예요?")).isEmpty();
		// 조회 자체를 하지 않는다(모바일 요금제를 대신 돌려주지 않는다).
		assertThat(lookup.groups).isEmpty();
	}

	@Test
	void keepsTheTwoArgumentVersionOnGeneralPlans() {
		RecordingLookup lookup = new RecordingLookup();
		lookup.findExtreme(PlanSortKey.CHEAPEST, 1);
		assertThat(lookup.groups).containsExactly("GENERAL");
	}

	private static PlanReference plan(String code, int fee, String dataPolicy, Long baseDataMb) {
		return new PlanReference(1L, code, code, "요약", fee, "설명", 1.0, null,
				"LTE_5G", "GENERAL", null, null, dataPolicy, baseDataMb, 1000, "UNLIMITED", null, "UNLIMITED", null);
	}

	/** 미리 정해 둔 정렬 결과를 돌려주고, 조건으로 거를 때 어떤 조건이 왔는지와 몇 개를 가져오라고 했는지 기록하는 가짜 구현. */
	private static final class FixedLookup implements PlanLookupService {
		final List<PlanReference> sorted;
		final java.util.function.Function<PlanQueryConditions, java.util.Set<String>> matcher;
		PlanQueryConditions receivedConditions;
		int fetched;

		FixedLookup(List<PlanReference> sorted, java.util.function.Function<PlanQueryConditions, java.util.Set<String>> matcher) {
			this.sorted = sorted;
			this.matcher = matcher;
		}

		@Override
		public List<PlanReference> findExtreme(PlanSortKey sortKey, int limit, String targetGroup) {
			fetched = limit;
			return sorted.stream().limit(limit).toList();
		}

		@Override
		public java.util.Set<String> matchingPlanCodes(PlanQueryConditions conditions) {
			receivedConditions = conditions;
			return matcher.apply(conditions);
		}
	}

	private static java.util.Set<String> codes(java.util.stream.Stream<PlanReference> plans) {
		return plans.map(PlanReference::planCode).collect(java.util.stream.Collectors.toSet());
	}

	@Test
	void picksTheCheapestPlanInsideThePriceBandNamedInTheQuestion() {
		List<PlanReference> byFee = List.of(plan("LITE-5", 25_000, "LIMITED", 5_120L), plan("LITE-10", 31_000, "LIMITED", 10_240L),
				plan("BALANCE-20", 37_000, "LIMITED", 20_480L), plan("MAX", 69_000, "UNLIMITED", null));
		FixedLookup lookup = new FixedLookup(byFee, conditions -> codes(byFee.stream()
				.filter(plan -> plan.monthlyFee() >= conditions.feeMin() && plan.monthlyFee() <= conditions.feeMax())));

		List<PlanReference> result = lookup.findExtremeForQuery(PlanSortKey.CHEAPEST, 1, "3만원대 중에서 가장 저렴한 요금제");

		assertThat(result).extracting(PlanReference::planCode).containsExactly("LITE-10");
		assertThat(lookup.receivedConditions.feeMin()).isEqualTo(30_000);
		assertThat(lookup.receivedConditions.feeMax()).isEqualTo(39_999);
		assertThat(lookup.fetched).isEqualTo(PlanLookupService.EXTREME_FETCH_LIMIT);
	}

	@Test
	void readsConditionsEvenWhenTheQuestionDoesNotSayPlan() {
		List<PlanReference> byData = List.of(plan("MAX", 69_000, "UNLIMITED", null), plan("PLUS-120", 59_000, "LIMITED", 122_880L),
				plan("PLUS-80", 51_000, "LIMITED", 81_920L), plan("BALANCE-40", 43_000, "LIMITED", 40_960L));
		FixedLookup lookup = new FixedLookup(byData,
				conditions -> codes(byData.stream().filter(plan -> plan.monthlyFee() <= conditions.feeMax())));

		List<PlanReference> result = lookup.findExtremeForQuery(PlanSortKey.MOST_DATA, 1, "5만원 이하 중에 데이터 제일 많이 주는 거");

		assertThat(result).extracting(PlanReference::planCode).containsExactly("BALANCE-40");
	}

	@Test
	void picksTheCheapestUnlimitedPlanWhenTheQuestionSaysUnlimited() {
		List<PlanReference> byFee = List.of(plan("LITE-5", 25_000, "LIMITED", 5_120L), plan("MAX", 69_000, "UNLIMITED", null),
				plan("MAX-SHARE", 75_000, "UNLIMITED", null));
		FixedLookup lookup = new FixedLookup(byFee,
				conditions -> codes(byFee.stream().filter(plan -> plan.dataPolicy().equals(conditions.dataPolicy()))));

		List<PlanReference> result = lookup.findExtremeForQuery(PlanSortKey.CHEAPEST, 1, "무제한 요금제 중에 제일 싼 거");

		assertThat(result).extracting(PlanReference::planCode).containsExactly("MAX");
	}

	@Test
	void doesNotFilterWhenTheQuestionHasNoConditionsAndKeepsTies() {
		List<PlanReference> byData = List.of(plan("MAX", 69_000, "UNLIMITED", null), plan("MAX-SHARE", 75_000, "UNLIMITED", null),
				plan("PLUS-120", 59_000, "LIMITED", 122_880L));
		FixedLookup lookup = new FixedLookup(byData, conditions -> java.util.Set.of());

		List<PlanReference> result = lookup.findExtremeForQuery(PlanSortKey.MOST_DATA, 1, "데이터 제일 많이 주는 요금제?");

		// 조건이 없으면 거르지 않는다(matchingPlanCodes를 부르지 않는다). 무제한이 둘이라 하나만 답하지 않는다.
		assertThat(lookup.receivedConditions).isNull();
		assertThat(result).extracting(PlanReference::planCode).containsExactly("MAX", "MAX-SHARE");
		assertThat(lookup.fetched).isEqualTo(1 + PlanLookupService.MAX_TIED_EXTRA);
	}

	@Test
	void returnsNothingWhenNoPlanSatisfiesTheConditions() {
		List<PlanReference> byFee = List.of(plan("LITE-5", 25_000, "LIMITED", 5_120L));
		FixedLookup lookup = new FixedLookup(byFee, conditions -> java.util.Set.of());

		assertThat(lookup.findExtremeForQuery(PlanSortKey.CHEAPEST, 1, "1만원대 중에서 가장 저렴한 요금제")).isEmpty();
	}

	@Test
	void limitWithTiesAddsPlansWithTheSameSortValueButOnlyUpToTheCap() {
		List<PlanReference> sameFee = List.of(plan("A", 30_000, "LIMITED", 1L), plan("B", 30_000, "LIMITED", 2L),
				plan("C", 30_000, "LIMITED", 3L), plan("D", 30_000, "LIMITED", 4L), plan("E", 40_000, "LIMITED", 5L));

		assertThat(PlanLookupService.limitWithTies(sameFee, PlanSortKey.CHEAPEST, 1))
				.extracting(PlanReference::planCode).containsExactly("A", "B", "C");
		assertThat(PlanLookupService.limitWithTies(sameFee, PlanSortKey.CHEAPEST, 4))
				.extracting(PlanReference::planCode).containsExactly("A", "B", "C", "D");
		assertThat(PlanLookupService.limitWithTies(sameFee, PlanSortKey.CHEAPEST, 9)).hasSize(5);
	}

	@Test
	void limitWithTiesDoesNotAddPlansWithADifferentSortValue() {
		List<PlanReference> byData = List.of(plan("MAX", 69_000, "UNLIMITED", null), plan("PLUS-120", 59_000, "LIMITED", 122_880L));

		assertThat(PlanLookupService.limitWithTies(byData, PlanSortKey.MOST_DATA, 1))
				.extracting(PlanReference::planCode).containsExactly("MAX");
		assertThat(PlanLookupService.limitWithTies(byData, PlanSortKey.MOST_DATA, 0)).isEmpty();
	}

	@Test
	void rejectsALimitBelowOne() {
		RecordingLookup lookup = new RecordingLookup();
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> lookup.findExtremeForQuery(PlanSortKey.CHEAPEST, 0, "가장 싼 요금제"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
