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
	void detectsCompetitorWordsThatAreNotBrandNamesButAlwaysMeanOtherCarriers() {
		assertThat(detect("타사 결합상품이랑 비교해줘")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("경쟁사보다 나은 점이 뭐예요?")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("U+ 고객센터 번호 알려줘")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("SK 쪽 인터넷 속도는 어떤가요?")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("통신사끼리 로밍 요금 차이가 있나요?")).contains(Rule.COMPETITOR_BRAND);
	}

	@Test
	void detectsGenericCompetitorExpressionsOnlyWithComparisonWords() {
		assertThat(detect("다른 통신사 해지하고 오면 혜택 있어?")).contains(Rule.COMPETITOR_GENERIC);
		assertThat(detect("다른 통신사 요금제가 여기보다 싸다던데요")).contains(Rule.COMPETITOR_GENERIC);
		assertThat(detect("통신사 비교 사이트 추천해줘")).contains(Rule.COMPETITOR_GENERIC);
		assertThat(detect("타 통신사에서 왔는데 결합할인 받을 수 있나요?")).isEmpty();
	}

	@Test
	void detectsPersonalLookupWithMoreRequestAndStatusExpressions() {
		assertThat(detect("제 남은 데이터 좀 알려주세요")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("지금 제 요금제가 뭔지 궁금해요")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("저 지금 로밍 가입돼 있나요?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("제 명의로 개통된 회선 조회해줘")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내 포인트 잔액 확인해줘")).contains(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void doesNotFlagQuestionsAboutWhereToCheckMyInfo() {
		assertThat(detect("제 요금제에서 데이터 얼마나 주는지 확인하는 곳이 어디예요?")).doesNotContain(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void doesNotFlagProblemOrPermissionQuestionsThatMentionLookupOrUnpaidWords() {
		assertThat(detect("유심 업데이트 대상 조회에 제 번호가 안 나와요.")).isEmpty();
		assertThat(detect("가족이 제 미납요금을 대신 납부해도 되나요?")).isEmpty();
		assertThat(detect("제 통신요금 미납된 게 있는지 확인해줘")).contains(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void doesNotFlagReportOrProcessingHowToQuestionsThatMentionMyName() {
		assertThat(detect("모르는 휴대폰이나 인터넷이 제 명의로 가입되어 있어요. 어떻게 신고하나요?")).isEmpty();
		assertThat(detect("제 명의로 가입된 회선이 도용된 것 같아서 신고하고 싶어요")).isEmpty();
		// 상태를 묻는 표현("어떻게 돼")은 여전히 조회로 본다.
		assertThat(detect("내 유심 상태가 어떻게 돼?")).contains(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void doesNotFlagHowToQuestionsThatContainRequestWords() {
		assertThat(detect("제 스마트폰에서 스팸 문자 차단하는 방법 알려주세요")).isEmpty();
		assertThat(detect("제 데이터를 가족에게 나눠주는 방법이 궁금해요")).isEmpty();
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
