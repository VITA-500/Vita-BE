package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NonMobilePlanQueryDetectorTest {

	@Test
	void detectsInternetAndIptvFeeQuestions() {
		assertThat(NonMobilePlanQueryDetector.matches("인터넷 월 요금은 얼마예요?")).isTrue();
		assertThat(NonMobilePlanQueryDetector.matches("IPTV 월 요금이 얼마예요?")).isTrue();
		assertThat(NonMobilePlanQueryDetector.matches("인터넷 요금제 뭐 있어?")).isTrue();
		assertThat(NonMobilePlanQueryDetector.matches("인터넷 요금 할인 받는 방법")).isTrue();
		assertThat(NonMobilePlanQueryDetector.matches("기가 인터넷 요금 알려줘")).isTrue();
		assertThat(NonMobilePlanQueryDetector.matches("TV 요금제 가격 알려줘")).isTrue();
		assertThat(NonMobilePlanQueryDetector.matches("아이피티비 상품 뭐 있어요?")).isTrue();
		assertThat(NonMobilePlanQueryDetector.matches("인터넷전화 요금제는 얼마예요?")).isTrue();
		assertThat(NonMobilePlanQueryDetector.matches("CCTV 설치 비용이 궁금해요")).isTrue();
		assertThat(NonMobilePlanQueryDetector.matches("인터넷 약정 기간이 있나요?")).isTrue();
		assertThat(NonMobilePlanQueryDetector.matches("IPTV 얼마예요?")).isTrue();
	}

	@Test
	void doesNotFlagMobilePlanQuestionsThatMentionInternetOrTv() {
		assertThat(NonMobilePlanQueryDetector.matches("인터넷 많이 쓰는데 요금제 추천해줘")).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("TV 많이 보는데 어떤 요금제가 좋아요?")).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("인터넷 무제한 요금제 있어?")).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("영상 많이 보는데 어떤 요금제가 좋아요?")).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("데이터 무제한 요금제 있어?")).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("3만원대 요금제 뭐 있어?")).isFalse();
	}

	@Test
	void doesNotFlagWhenMobileIsMentioned() {
		assertThat(NonMobilePlanQueryDetector.matches("모바일 인터넷 요금제 있어?")).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("휴대폰 인터넷 요금 얼마야?")).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("폰 인터넷 요금제 추천해줘")).isFalse();
	}

	@Test
	void doesNotFlagFaqStyleQuestionsAboutInternetOrTvServiceWithoutFee() {
		assertThat(NonMobilePlanQueryDetector.matches("인터넷이 자꾸 끊겨요")).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("IPTV 리모컨이 안 돼요")).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("TV 채널이 안 나와요")).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("우리 동네에서 인터넷 설치가 되나요?")).isFalse();
	}

	@Test
	void handlesNullAndBlank() {
		assertThat(NonMobilePlanQueryDetector.matches(null)).isFalse();
		assertThat(NonMobilePlanQueryDetector.matches("  ")).isFalse();
	}
}
