package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 원문에서 읽은 조건과 요금제용 질문에서 읽은 조건을 합치는 규칙을 검증한다. */
class PlanQueryConditionsTest {

	private static PlanQueryConditions of(Integer feeMin, Integer feeMax, Long dataMin, Long dataMax, String group, String policy) {
		return new PlanQueryConditions(feeMin, feeMax, dataMin, dataMax, group, policy);
	}

	@Test
	void keepsTheOwnValuesAndFillsOnlyTheMissingGroups() {
		PlanQueryConditions original = of(null, 50_000, null, null, null, null);
		PlanQueryConditions fallback = of(30_000, 39_999, 10_240L, null, "YOUTH", "LIMITED");

		PlanQueryConditions merged = original.orElse(fallback);

		// 가격 범위는 원문의 것만 쓰고(상한 5만원, 하한 없음), 나머지 항목은 보충한다.
		assertThat(merged.feeMin()).isNull();
		assertThat(merged.feeMax()).isEqualTo(50_000);
		assertThat(merged.dataMbMin()).isEqualTo(10_240L);
		assertThat(merged.targetGroup()).isEqualTo("YOUTH");
		assertThat(merged.dataPolicy()).isEqualTo("LIMITED");
	}

	@Test
	void takesEverythingFromTheFallbackWhenTheOwnConditionsAreEmpty() {
		PlanQueryConditions empty = of(null, null, null, null, null, null);
		PlanQueryConditions fallback = of(30_000, 39_999, null, 20_480L, "SENIOR", null);

		assertThat(empty.orElse(fallback)).isEqualTo(fallback);
	}

	@Test
	void staysEmptyWhenBothAreEmpty() {
		PlanQueryConditions empty = of(null, null, null, null, null, null);

		assertThat(empty.orElse(empty).isEmpty()).isTrue();
	}

	private static PlanQueryConditions withVoiceSms(String voice, String sms) {
		return new PlanQueryConditions(null, null, null, null, null, null, voice, sms);
	}

	@Test
	void isNotEmptyWhenOnlyAVoiceOrSmsPolicyIsSet() {
		assertThat(withVoiceSms("UNLIMITED", null).isEmpty()).isFalse();
		assertThat(withVoiceSms(null, "NONE").isEmpty()).isFalse();
		assertThat(withVoiceSms(null, null).isEmpty()).isTrue();
	}

	@Test
	void fillsVoiceAndSmsPoliciesSeparatelyAndKeepsTheOwnOnes() {
		PlanQueryConditions original = withVoiceSms("UNLIMITED", null);
		PlanQueryConditions fallback = withVoiceSms("NONE", "UNLIMITED");

		PlanQueryConditions merged = original.orElse(fallback);

		assertThat(merged.voicePolicy()).isEqualTo("UNLIMITED");
		assertThat(merged.smsPolicy()).isEqualTo("UNLIMITED");
	}

	@Test
	void dropsOnlyTheVoiceAndSmsPoliciesWhenRelaxing() {
		PlanQueryConditions all = new PlanQueryConditions(null, 50_000, null, null, "YOUTH", "UNLIMITED", "UNLIMITED", "UNLIMITED");

		PlanQueryConditions relaxed = all.withoutVoiceSmsPolicy();

		assertThat(relaxed.voicePolicy()).isNull();
		assertThat(relaxed.smsPolicy()).isNull();
		assertThat(relaxed.feeMax()).isEqualTo(50_000);
		assertThat(relaxed.targetGroup()).isEqualTo("YOUTH");
		assertThat(relaxed.dataPolicy()).isEqualTo("UNLIMITED");
		// 데이터 정책만 빼는 쪽은 통화·문자 정책을 그대로 둔다.
		assertThat(all.withoutDataPolicy().voicePolicy()).isEqualTo("UNLIMITED");
	}

	private static PlanQueryConditions excluding(String group, java.util.Set<String> excluded) {
		return new PlanQueryConditions(null, null, null, null, group, null, null, null, excluded);
	}

	@Test
	void unitesTheExcludedGroupsOfBothSidesButNeverExcludesTheChosenGroup() {
		PlanQueryConditions original = excluding(null, java.util.Set.of("SENIOR"));
		PlanQueryConditions fallback = excluding("YOUTH", java.util.Set.of("YOUTH", "KIDS"));

		PlanQueryConditions merged = original.orElse(fallback);

		assertThat(merged.targetGroup()).isEqualTo("YOUTH");
		assertThat(merged.excludedGroups()).containsExactlyInAnyOrder("SENIOR", "KIDS");
	}

	@Test
	void doesNotCountExcludedGroupsAsConditionsAndKeepsThemWhenRelaxing() {
		PlanQueryConditions conditions = new PlanQueryConditions(null, 50_000, null, null, null, "UNLIMITED", "UNLIMITED", null,
				java.util.Set.of("SENIOR"));

		assertThat(excluding(null, java.util.Set.of("SENIOR")).isEmpty()).isTrue();
		assertThat(excluding(null, java.util.Set.of("SENIOR")).hasExclusions()).isTrue();
		assertThat(conditions.withoutDataPolicy().excludedGroups()).containsExactly("SENIOR");
		assertThat(conditions.withoutVoiceSmsPolicy().excludedGroups()).containsExactly("SENIOR");
	}

	@Test
	void keepsTheOwnConditionsWhenTheFallbackAddsNothing() {
		PlanQueryConditions own = of(30_000, 39_999, null, null, "GENERAL", "UNLIMITED");

		assertThat(own.orElse(of(null, null, null, null, null, null))).isEqualTo(own);
	}
}
