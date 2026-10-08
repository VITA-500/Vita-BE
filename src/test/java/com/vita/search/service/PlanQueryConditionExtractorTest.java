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
	void treatsDataWorryFreeWithEitherEndingAsUnlimited() {
		assertThat(extract("데이터 걱정 없는 요금제 추천해줘").dataPolicy()).isEqualTo("UNLIMITED");
	}

	@Test
	void extractsVoiceAndSmsUnlimitedSeparatelyFromData() {
		PlanQueryConditions voice = extract("통화 무제한 요금제");
		assertThat(voice.voicePolicy()).isEqualTo("UNLIMITED");
		assertThat(voice.smsPolicy()).isNull();
		assertThat(voice.dataPolicy()).isNull();

		PlanQueryConditions sms = extract("문자 무제한 되는 요금제");
		assertThat(sms.smsPolicy()).isEqualTo("UNLIMITED");
		assertThat(sms.voicePolicy()).isNull();
		assertThat(sms.dataPolicy()).isNull();

		assertThat(extract("음성통화 무제한 요금제").voicePolicy()).isEqualTo("UNLIMITED");
		assertThat(extract("SMS 무제한 요금제 있어?").smsPolicy()).isEqualTo("UNLIMITED");
	}

	@Test
	void readsEveryNounOfAnUnlimitedChain() {
		for (String query : java.util.List.of("통화랑 문자 무제한인 요금제 있어?", "통화, 문자 무제한 요금제", "통화 문자 무제한 요금제 알려줘",
				"통화·문자 무제한 요금제", "통화와 문자 무제한 요금제")) {
			PlanQueryConditions c = extract(query);
			assertThat(c.voicePolicy()).as(query).isEqualTo("UNLIMITED");
			assertThat(c.smsPolicy()).as(query).isEqualTo("UNLIMITED");
			assertThat(c.dataPolicy()).as(query).isNull();
		}
	}

	@Test
	void givesEachUnlimitedExpressionItsOwnTarget() {
		PlanQueryConditions voiceAndData = extract("통화도 데이터도 무제한인 요금제");
		assertThat(voiceAndData.voicePolicy()).isEqualTo("UNLIMITED");
		assertThat(voiceAndData.dataPolicy()).isEqualTo("UNLIMITED");
		assertThat(voiceAndData.smsPolicy()).isNull();

		PlanQueryConditions separate = extract("데이터 무제한이고 통화도 무제한인 요금제");
		assertThat(separate.dataPolicy()).isEqualTo("UNLIMITED");
		assertThat(separate.voicePolicy()).isEqualTo("UNLIMITED");

		// 변환된 요금제용 질문 형태: 줄마다 무제한 표현이 따로 붙는다.
		PlanQueryConditions labeled = extract("조건: 통화 무제한, 문자 무제한\n핵심 키워드: 통화 무제한, 문자 무제한, 요금제");
		assertThat(labeled.voicePolicy()).isEqualTo("UNLIMITED");
		assertThat(labeled.smsPolicy()).isEqualTo("UNLIMITED");
		assertThat(labeled.dataPolicy()).isNull();

		// 통화 무제한과 데이터량을 함께 말해도 데이터량은 그대로 읽고, 무제한은 통화에만 붙는다.
		PlanQueryConditions withAmount = extract("통화 무제한이면서 데이터 20기가 요금제");
		assertThat(withAmount.voicePolicy()).isEqualTo("UNLIMITED");
		assertThat(withAmount.dataPolicy()).isNull();
		assertThat(withAmount.dataMbMin()).isEqualTo(20_480L);
	}

	@Test
	void readsMaeumkkeutBySubjectAndKeepsPlainOnesAsDataUnlimited() {
		PlanQueryConditions callsAndTexts = extract("통화와 문자를 마음껏 쓸 수 있는 요금제가 뭐예요?");
		assertThat(callsAndTexts.voicePolicy()).isEqualTo("UNLIMITED");
		assertThat(callsAndTexts.smsPolicy()).isEqualTo("UNLIMITED");
		assertThat(callsAndTexts.dataPolicy()).isNull();

		assertThat(extract("데이터 마음껏 쓰는 요금제").dataPolicy()).isEqualTo("UNLIMITED");
		assertThat(extract("맘껏 쓸 수 있는 요금제 있어?").dataPolicy()).isEqualTo("UNLIMITED");
		assertThat(extract("무제한 요금제 있어?").dataPolicy()).isEqualTo("UNLIMITED");
	}

	@Test
	void readsTheNounAfterAnUnlimitedExpressionOnlyWhenNothingPrecedesIt() {
		PlanQueryConditions c = extract("무제한 통화 되는 요금제");

		assertThat(c.voicePolicy()).isEqualTo("UNLIMITED");
		assertThat(c.dataPolicy()).isNull();
	}

	@Test
	void doesNotReadANegatedVoiceOrSmsUnlimited() {
		PlanQueryConditions c = extract("통화 무제한 아닌 요금제 있어?");

		assertThat(c.voicePolicy()).isNull();
		assertThat(c.dataPolicy()).isNull();
	}

	@Test
	void extractsPlansWhereVoiceOrSmsIsNotAvailable() {
		assertThat(extract("문자 안 되는 요금제는 뭐야?").smsPolicy()).isEqualTo("NONE");
		assertThat(extract("통화가 없는 요금제 알려줘").voicePolicy()).isEqualTo("NONE");
		assertThat(extract("음성통화 없이 데이터만 제공하는 요금제가 있나요?").voicePolicy()).isEqualTo("NONE");

		PlanQueryConditions both = extract("통화 문자 안 되는 요금제 있어?");
		assertThat(both.voicePolicy()).isEqualTo("NONE");
		assertThat(both.smsPolicy()).isEqualTo("NONE");
	}

	@Test
	void ignoresTroubleReportsAndPhrasesThatAreNotVoiceOrSmsConditions() {
		// 장애 문의는 "안 돼요"로 끝난다. 이것을 NONE으로 읽으면 워치·태블릿 요금제가 나오는 오탐이 된다.
		assertThat(extract("요금제 바꿨는데 통화가 안 돼요").voicePolicy()).isNull();
		assertThat(extract("요금제 변경 후 문자가 안 와요").smsPolicy()).isNull();
		assertThat(extract("요금제 변경 후 통화가 안 되는 곳이 있어요").voicePolicy()).isNull();
		assertThat(extract("고객센터 전화번호 알려주는 요금제 상담").voicePolicy()).isNull();
		assertThat(extract("전화 상담 가능한 요금제 있어?").voicePolicy()).isNull();
		assertThat(extract("통화 품질 좋은 요금제 추천해줘").voicePolicy()).isNull();
	}

	@Test
	void readsConditionsEvenWhenThePlanWordIsMisspelled() {
		assertThat(extract("3만7천원 요금재 있어요?").feeMin()).isEqualTo(37_000);
		assertThat(extract("데이터 40기가 요근제 알려줘").dataMbMin()).isEqualTo(40_960L);
		assertThat(extract("3만원대 요금재 알려줘").feeMax()).isEqualTo(39_999);
		assertThat(extract("청년 요금재 추천").targetGroup()).isEqualTo("YOUTH");
		assertThat(extract("시니어 요금지 알려줘").targetGroup()).isEqualTo("SENIOR");
		assertThat(extract("시니어 요금지가 뭐야").targetGroup()).isEqualTo("SENIOR");
	}

	@Test
	void readsUnlimitedEvenWhenItIsMisspelled() {
		assertThat(extract("무재한 요금제 알려줘").dataPolicy()).isEqualTo("UNLIMITED");
		assertThat(extract("데이터 무재한 요금재 있어?").dataPolicy()).isEqualTo("UNLIMITED");
	}

	@Test
	void doesNotTreatRealWordsThatStartLikeTheTyposAsThePlanWord() {
		// "요금지급"은 실제 단어라 "요금제"로 고치면 안 된다. 요금제 질문이 아니므로 조건이 나오지 않아야 한다.
		assertThat(extract("3만원 요금지급일 알려줘").isEmpty()).isTrue();
		assertThat(extract("요금지급 방법 5기가").isEmpty()).isTrue();
	}

	@Test
	void doesNotReadDiscountCouponGiftOrFeeAmountsAsTheMonthlyFee() {
		assertThat(extract("3만원 할인 쿠폰 주는 요금제").isEmpty()).isTrue();
		assertThat(extract("5천원 할인 받는 요금제 있어?").isEmpty()).isTrue();
		assertThat(extract("가입하면 10만원 상품권 주는 요금제").isEmpty()).isTrue();
		assertThat(extract("요금제 바꾸면 5천원 더 내요?").isEmpty()).isTrue();
		assertThat(extract("요금제 변경 수수료 3천원이라던데 맞아요?").isEmpty()).isTrue();
		assertThat(extract("수수료 3,000원 드는 요금제 변경").isEmpty()).isTrue();
	}

	@Test
	void readsTheMonthlyFeeThatRemainsAfterIgnoringADiscountAmount() {
		PlanQueryConditions c = extract("3만원 할인 쿠폰 주는 5만원 이하 요금제");

		assertThat(c.feeMax()).isEqualTo(50_000);
		assertThat(c.feeMin()).isNull();
	}

	@Test
	void keepsReadingAFeeWhenADiscountWordOnlyAppearsAfterTheComparison() {
		assertThat(extract("3만원 이하 쿠폰 주는 요금제").feeMax()).isEqualTo(30_000);
		assertThat(extract("3만원대 요금제 중에 할인 되는 거").feeMin()).isEqualTo(30_000);
	}

	@Test
	void doesNotReadRoamingCouponOrAddOnDataAsTheBaseData() {
		assertThat(extract("로밍으로 5기가 쓰면 요금제 어떻게 돼요?").isEmpty()).isTrue();
		assertThat(extract("추가 데이터 2기가 쿠폰 주는 요금제").isEmpty()).isTrue();
		assertThat(extract("쿠폰 2기가 주는 요금제").isEmpty()).isTrue();
	}

	@Test
	void keepsReadingBaseDataWhenRoamingIsOnlyMentionedElsewhere() {
		assertThat(extract("로밍 가능한 20기가 요금제").dataMbMin()).isEqualTo(20_480L);
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

	@Test
	void targetGroupOfFindsTheGroupEvenWithoutThePlanWord() {
		assertThat(PlanQueryConditionExtractor.targetGroupOf("워치 중에 제일 싼 거 알려줘")).isEqualTo("WATCH");
		assertThat(PlanQueryConditionExtractor.targetGroupOf("태블릿 데이터 가장 많이 주는 건?")).isEqualTo("TABLET");
		assertThat(PlanQueryConditionExtractor.targetGroupOf("청년 요금제 중에 가장 저렴한 거")).isEqualTo("YOUTH");
		assertThat(PlanQueryConditionExtractor.targetGroupOf("시니어 요금제 중 제일 싼 요금제가 뭐예요?")).isEqualTo("SENIOR");
	}

	@Test
	void targetGroupOfIsNullWhenNoGroupOrSeveralGroupsAreMentioned() {
		assertThat(PlanQueryConditionExtractor.targetGroupOf("가장 저렴한 요금제 알려줘")).isNull();
		assertThat(PlanQueryConditionExtractor.targetGroupOf("청년이랑 시니어 중 제일 싼 요금제")).isNull();
		assertThat(PlanQueryConditionExtractor.targetGroupOf(null)).isNull();
	}
}
