package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vita.search.service.IrrelevantQueryDetector.Rule;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IrrelevantQueryDetectorTest {

	private static Set<Rule> detect(String query) {
		return IrrelevantQueryDetector.detect(query);
	}

	@Test
	void detectsPersonalLookupQuestions() {
		assertThat(detect("내가 가입한 요금제가 뭐야?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내 이번 달 요금 얼마 나왔어?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("나 지금 데이터 얼마나 썼어?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내 로밍 신청 내역 보여줘")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내 회선 정지 상태야?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내 계약 만료일이 언제야?")).contains(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void doesNotFlagHowToQuestionsEvenWithFirstPerson() {
		assertThat(detect("제 요금제를 변경하고 싶어요")).doesNotContain(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내 요금제 변경하는 방법이 뭐야?")).doesNotContain(Rule.PERSONAL_LOOKUP);
		assertThat(detect("저는 로밍을 어떻게 신청해요?")).doesNotContain(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void doesNotTreatWeOrRegionQuestionsAsPersonal() {
		assertThat(detect("인터넷전화 우리 동네에서도 되나요?")).isEmpty();
		assertThat(detect("신청 안 하고 그냥 나가면 로밍 안 되나요?")).isEmpty();
	}

	@Test
	void doesNotFlagLookupWordsWithoutFirstPerson() {
		assertThat(detect("이번 달 통신요금 청구액을 확인하고 싶어요.")).isEmpty();
		assertThat(detect("요금제 기본 데이터는 얼마인가요?")).isEmpty();
	}

	@Test
	void detectsCompetitorBrandNames() {
		assertThat(detect("SKT 요금제랑 비교하면 어때?")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("KT는 더 싸다는데 맞아?")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("LG유플러스랑 뭐가 달라?")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("알뜰폰으로 갈아타려는데 어때?")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("통신 3사 중에 어디가 제일 나아?")).contains(Rule.COMPETITOR_BRAND);
	}

	@Test
	void doesNotTreatEnglishWordsContainingKtAsBrand() {
		assertThat(detect("KTX 안에서 와이파이 되나요?")).doesNotContain(Rule.COMPETITOR_BRAND);
	}

	@Test
	void detectsGenericCompetitorExpressionsOnlyWithComparisonWords() {
		Set<Rule> rules = detect("타사 결합상품이랑 비교해줘");
		assertThat(rules).contains(Rule.COMPETITOR_GENERIC);
		assertThat(rules).doesNotContain(Rule.COMPETITOR_BRAND);
		assertThat(detect("다른 통신사 해지하고 오면 혜택 있어?")).contains(Rule.COMPETITOR_GENERIC);
		assertThat(detect("통신사 비교 사이트 추천해줘")).contains(Rule.COMPETITOR_GENERIC);
	}

	@Test
	void doesNotFlagNumberPortingQuestionsMentioningOtherCarrier() {
		assertThat(detect("인터넷을 다른 통신사에서 옮겨오려면 무엇을 확인해야 하나요?")).isEmpty();
		assertThat(detect("번호이동을 신청하면 기존 통신사는 직접 해지해야 하나요?")).isEmpty();
	}

	@Test
	void doesNotFlagProblemDescriptionsThatMentionMyHistory() {
		assertThat(detect("가족 명의 회선의 청구 내역이 제 내역과 함께 보여요.")).isEmpty();
		assertThat(detect("로밍 이용내역에 제가 방문하지 않은 국가가 표시돼요.")).isEmpty();
	}

	@Test
	void returnsEmptyForNullBlankOrPlainQuestions() {
		assertThat(detect(null)).isEmpty();
		assertThat(detect("  ")).isEmpty();
		assertThat(detect("자동이체 계좌를 변경하고 싶어요")).isEmpty();
	}
}
