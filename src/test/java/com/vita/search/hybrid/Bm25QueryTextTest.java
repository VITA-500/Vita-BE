package com.vita.search.hybrid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Bm25QueryTextTest {

	@Test
	void stripsOnlyTheLeadingLabelOfEachLineAndKeepsAllValues() {
		String faqQuery = "조건: 이사 전 인터넷 일시정지\n질문: 인터넷을 잠깐 멈출 수 있는 기간과 횟수는 어떻게 되나요?\n핵심 키워드: 인터넷, 일시정지, 기간";

		assertThat(Bm25QueryText.from(faqQuery))
				.isEqualTo("이사 전 인터넷 일시정지 인터넷을 잠깐 멈출 수 있는 기간과 횟수는 어떻게 되나요? 인터넷, 일시정지, 기간");
	}

	@Test
	void stripsThePlanQueryLabels() {
		String planQuery = "금액: 3만원대\n데이터: 20GB 이상\n대상: 청년\n핵심 키워드: 3만원대, 20GB 이상, 요금제";

		assertThat(Bm25QueryText.from(planQuery)).isEqualTo("3만원대 20GB 이상 청년 3만원대, 20GB 이상, 요금제");
	}

	@Test
	void keepsTextWithoutLabelsAsIs() {
		assertThat(Bm25QueryText.from("비타 플러스 80 요금제 알려줘")).isEqualTo("비타 플러스 80 요금제 알려줘");
	}

	@Test
	void doesNotStripALabelWordInTheMiddleOfALine() {
		assertThat(Bm25QueryText.from("핵심 키워드: 질문: 포함 조건: 확인")).isEqualTo("질문: 포함 조건: 확인");
	}

	@Test
	void fallsBackToTheTrimmedTextWhenOnlyLabelsRemain() {
		assertThat(Bm25QueryText.from("  핵심 키워드:  ")).isEqualTo("핵심 키워드:");
	}

	@Test
	void returnsAnEmptyStringForNullOrBlank() {
		assertThat(Bm25QueryText.from(null)).isEmpty();
		assertThat(Bm25QueryText.from("   \n ")).isEmpty();
	}
}
