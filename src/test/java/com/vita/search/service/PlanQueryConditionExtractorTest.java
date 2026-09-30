package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlanQueryConditionExtractorTest {

	private static PlanQueryConditions extract(String query) {
		return PlanQueryConditionExtractor.extract(query);
	}

	@Test
	void extractsKoreanWonAmountAsExactFee() {
		PlanQueryConditions c = extract("3만1천원 요금제 있어?");

		assertThat(c.feeMin()).isEqualTo(31_000);
		assertThat(c.feeMax()).isEqualTo(31_000);
	}

	@Test
	void extractsWholeManWonAndCheonWon() {
		assertThat(extract("3만원짜리 요금제 있어?").feeMin()).isEqualTo(30_000);
		assertThat(extract("5천원 요금제 있어?").feeMin()).isEqualTo(5_000);
	}

	@Test
	void extractsDigitWonAmountWithAndWithoutComma() {
		assertThat(extract("31,000원 요금제 뭐야?").feeMin()).isEqualTo(31_000);
		assertThat(extract("31000원 요금제 뭐야?").feeMin()).isEqualTo(31_000);
	}

	@Test
	void extractsFeeBoundsFromSuffix() {
		PlanQueryConditions atMost = extract("3만원 이하 요금제 있어?");
		assertThat(atMost.feeMin()).isNull();
		assertThat(atMost.feeMax()).isEqualTo(30_000);

		PlanQueryConditions under = extract("3만원 미만 요금제 있어?");
		assertThat(under.feeMax()).isEqualTo(29_999);

		PlanQueryConditions atLeast = extract("5만원 이상 요금제 알려줘");
		assertThat(atLeast.feeMin()).isEqualTo(50_000);
		assertThat(atLeast.feeMax()).isNull();

		PlanQueryConditions band = extract("3만원대 요금제 있어?");
		assertThat(band.feeMin()).isEqualTo(30_000);
		assertThat(band.feeMax()).isEqualTo(39_999);
	}

	@Test
	void ignoresFeeWhenMoreThanOneAmountIsMentioned() {
		PlanQueryConditions c = extract("3만원에서 5만원 사이 요금제 있어?");

		assertThat(c.feeMin()).isNull();
		assertThat(c.feeMax()).isNull();
	}

	@Test
	void treatsCannotExceedPhrasesAsUpperBoundNotLowerBound() {
		PlanQueryConditions notExceed = extract("3만원 안 넘는 요금제 있어?");
		assertThat(notExceed.feeMin()).isNull();
		assertThat(notExceed.feeMax()).isEqualTo(30_000);

		assertThat(extract("3만원 넘지 않는 요금제 알려줘").feeMax()).isEqualTo(30_000);
		assertThat(extract("3만원 못 넘는 요금제 있어?").feeMax()).isEqualTo(30_000);

		// 그냥 "넘는"은 기존대로 초과(하한)다.
		PlanQueryConditions exceed = extract("3만원 넘는 요금제 있어?");
		assertThat(exceed.feeMin()).isEqualTo(30_001);
		assertThat(exceed.feeMax()).isNull();
	}

	@Test
	void turnsApproximatePhrasesIntoRanges() {
		PlanQueryConditions fee = extract("3만원 정도 하는 요금제 있어?");
		assertThat(fee.feeMin()).isEqualTo(27_000);
		assertThat(fee.feeMax()).isEqualTo(33_000);

		PlanQueryConditions data = extract("데이터 20기가쯤 주는 요금제 있어?");
		assertThat(data.dataMbMin()).isEqualTo(15_360L);
		assertThat(data.dataMbMax()).isEqualTo(25_600L);
	}

	@Test
	void detectsDevicePlanMentionsEvenWithoutPlanWord() {
		assertThat(PlanQueryConditionExtractor.mentionsDevicePlan("스마트워치 데이터 얼마나 줘?")).isTrue();
		assertThat(PlanQueryConditionExtractor.mentionsDevicePlan("태블릿용 요금제 있어?")).isTrue();
		assertThat(PlanQueryConditionExtractor.mentionsDevicePlan("아이패드에 쓸 수 있어?")).isTrue();
		assertThat(PlanQueryConditionExtractor.mentionsDevicePlan("가장 저렴한 요금제 뭐야?")).isFalse();
		assertThat(PlanQueryConditionExtractor.mentionsDevicePlan(null)).isFalse();
	}

	@Test
	void extractsDataAmountInMegabytes() {
		PlanQueryConditions c = extract("데이터 20기가 주는 요금제 있어?");

		assertThat(c.dataMbMin()).isEqualTo(20_480L);
		assertThat(c.dataMbMax()).isEqualTo(20_480L);
		assertThat(extract("120GB 요금제 있어?").dataMbMin()).isEqualTo(122_880L);
	}

	@Test
	void extractsDataLowerBound() {
		PlanQueryConditions c = extract("80기가 이상 주는 요금제 있어?");

		assertThat(c.dataMbMin()).isEqualTo(81_920L);
		assertThat(c.dataMbMax()).isNull();
	}

	@Test
	void doesNotTreat5gNetworkAsDataAmount() {
		assertThat(extract("5G 요금제 있어?").dataMbMin()).isNull();
	}

	@Test
	void extractsTargetGroup() {
		assertThat(extract("청년 전용 요금제 있어?").targetGroup()).isEqualTo("YOUTH");
		assertThat(extract("부모님 드릴 시니어 요금제 추천해줘").targetGroup()).isEqualTo("SENIOR");
		assertThat(extract("아이 쓰기 좋은 요금제 있어?").targetGroup()).isEqualTo("KIDS");
		assertThat(extract("스마트워치용 요금제 있어?").targetGroup()).isEqualTo("WATCH");
		assertThat(extract("태블릿 전용 요금제 있어?").targetGroup()).isEqualTo("TABLET");
		assertThat(extract("일반 사용자용 요금제 추천해줘").targetGroup()).isEqualTo("GENERAL");
	}

	@Test
	void ignoresAmbiguousOrFalseTargetGroupWords() {
		assertThat(extract("청년이랑 시니어 요금제 비교해줘").targetGroup()).isNull();
		assertThat(extract("아이폰에 쓸 요금제 있어?").targetGroup()).isNull();
		assertThat(extract("일반적인 요금제 알려줘").targetGroup()).isNull();
	}

	@Test
	void extractsUnlimitedAndItsNegation() {
		assertThat(extract("데이터 무제한 요금제 있어?").dataPolicy()).isEqualTo("UNLIMITED");
		assertThat(extract("무제한 아니고 적당히 쓰는 요금제 있어?").dataPolicy()).isEqualTo("LIMITED");
		assertThat(extract("무제한은 아니고 많이 주는 요금제 있어?").dataPolicy()).isEqualTo("LIMITED");
		assertThat(extract("무제한 빼고 데이터 많이 주는 요금제 있어?").dataPolicy()).isEqualTo("LIMITED");
	}

	@Test
	void treatsWorryFreeDataParaphraseAsUnlimited() {
		assertThat(extract("데이터 걱정 없이 쓰고 싶은데 요금제 뭐가 좋아?").dataPolicy()).isEqualTo("UNLIMITED");
		assertThat(extract("데이터 마음껏 쓸 수 있는 요금제 있어?").dataPolicy()).isEqualTo("UNLIMITED");
	}

	@Test
	void doesNotTreatVoiceOrSmsUnlimitedAsDataPolicy() {
		assertThat(extract("통화 무제한인 요금제 있어?").dataPolicy()).isNull();
		assertThat(extract("문자 무제한인 요금제 알려줘").dataPolicy()).isNull();
	}

	@Test
	void combinesConditions() {
		PlanQueryConditions c = extract("3만5천원짜리 청년 요금제 있어?");

		assertThat(c.feeMin()).isEqualTo(35_000);
		assertThat(c.targetGroup()).isEqualTo("YOUTH");
	}

	@Test
	void returnsEmptyWhenQueryIsNotAboutPlans() {
		assertThat(extract("로밍 5기가 얼마야?").isEmpty()).isTrue();
		assertThat(extract("3만원 결제했는데 환불돼?").isEmpty()).isTrue();
		assertThat(extract(null).isEmpty()).isTrue();
	}

	@Test
	void doesNotTreatVitaminAsPlanNameButAcceptsRealPlanNames() {
		assertThat(extract("비타민 3만1천원짜리 추천해줘").isEmpty()).isTrue();
		assertThat(extract("3만 1천원짜리 점심 메뉴 추천해줘").isEmpty()).isTrue();
		assertThat(extract("비타 맥스 6만9천원 맞아?").feeMin()).isEqualTo(69_000);
		assertThat(extract("비타 라이트 3만1천원이야?").feeMin()).isEqualTo(31_000);
	}

	@Test
	void returnsEmptyForPlanQuestionWithoutConditions() {
		assertThat(extract("요금제 변경은 어떻게 해?").isEmpty()).isTrue();
		assertThat(extract("비타 라이트 5 요금제 설명해줘").isEmpty()).isTrue();
	}
}
