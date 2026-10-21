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
	void doesNotFlagMigrationOrCompatibilityQuestionsThatNameAnotherCarrier() {
		assertThat(detect("SKT에서 번호이동 하려면 어떻게 해요?")).isEmpty();
		assertThat(detect("KT 쓰다가 VITA로 넘어오고 싶어요")).isEmpty();
		assertThat(detect("알뜰폰에서 VITA로 옮기고 싶어요")).isEmpty();
		assertThat(detect("알뜰폰 가입자도 영상통화 되나요?")).isEmpty();
		assertThat(detect("LG유플러스 사용자한테도 메시지 보낼 수 있어요?")).isEmpty();
		// 실제 FAQ 질문이다. 타사·알뜰폰이 들어 있어도 VITA 이용에 관한 질문이라 FAQ가 답한다.
		assertThat(detect("VITA로 번호이동 후 다시 타사로 번호이동이 가능 한가요?")).isEmpty();
		assertThat(detect("영상통화는 타사 가입자와도 가능한가요?")).isEmpty();
		assertThat(detect("법인폰 본인확인 알뜰폰(MVNO) 고객도 이용 가능한가요?")).isEmpty();
		assertThat(detect("알뜰폰을 쓰는데 법인폰 본인확인을 이용할 수 있나요?")).isEmpty();
	}

	@Test
	void doesNotFlagComplaintsComparingSpeedWithOtherCarriers() {
		assertThat(detect("타사 보다 느린 것 같아요.")).isEmpty();
		assertThat(detect("타사 보다 인터넷이 느려요. 왜 그럴까요?")).isEmpty();
	}

	@Test
	void stillFlagsOtherCarrierPriceAndComparisonQuestionsEvenWhenTheyMentionSwitching() {
		assertThat(detect("SKT 요금제 가격 알려줘")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("알뜰폰 요금제 추천해줘")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("SKT로 갈아타면 더 싸요?")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("알뜰폰 쓰는 게 이득이야?")).contains(Rule.COMPETITOR_BRAND);
		assertThat(detect("타사 대비 혜택이 뭐야?")).contains(Rule.COMPETITOR_BRAND);
		// 번호이동 질문이어도 타사의 위약금을 묻는 것이라 VITA FAQ가 답할 수 없다.
		assertThat(detect("SKT에서 넘어오면 위약금 있어?")).contains(Rule.COMPETITOR_BRAND);
	}

	@Test
	void doesNotFlagQuestionsAboutWhenAPolicyApplies() {
		assertThat(detect("구글플레이에서 결제 취소했는데 내 통신요금에서는 언제 빠져?")).isEmpty();
		assertThat(detect("내 통신요금 자동이체는 언제부터 적용돼요?")).isEmpty();
		assertThat(detect("해지하면 제 개인정보는 언제 삭제되나요?")).isEmpty();
		// 계정의 실제 값(만료일, 소진 시점)을 묻는 질문은 여전히 조회로 본다.
		assertThat(detect("내 계약 만료일이 언제야?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내가 쓰는 요금제 데이터 소진 언제 돼?")).contains(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void doesNotFlagHowToReceiveOrAskAboutAvailability() {
		assertThat(detect("법적 분쟁 때문에 지난 몇 달치 내 발신 기록이 필요한데 어떻게 받을 수 있나요?")).isEmpty();
		assertThat(detect("온라인 간편 업데이트는 내가 원하는 시간에 언제든 가능한가요?")).isEmpty();
		assertThat(detect("제 청구서를 이메일로 어떻게 받아요?")).isEmpty();
	}

	@Test
	void doesNotTreatTheVerbNaAsFirstPersonButKeepsSentenceInitialNa() {
		assertThat(detect("유심 그거 언제까지 안 하면 큰일 나? 기한 있어?")).isEmpty();
		assertThat(detect("이거 안 하면 큰일 나? 기한 있어?")).isEmpty();
		assertThat(detect("나 이번 달 데이터 얼마나 썼어?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("아 근데 나 지금 요금 얼마 나왔어?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("저 지금 가입된 부가서비스 뭐 있어?")).contains(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void detectsPersonalUsageQuestionsWithoutFirstPerson() {
		assertThat(detect("지난달 요금이 얼마 나왔는지 알려줘")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("이번 달 데이터 남은 거 얼마야?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("이번 달 통화 몇 분 썼는지 알려줘")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("지난달 청구된 금액이 얼마였는지 확인해줘")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("어제 쓴 데이터 양이 얼마야?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("이번 달 문자 사용량 확인해 주세요")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("올해 로밍으로 쓴 금액이 총 얼마인지 궁금해요")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("남은 데이터가 얼마나 되는지 궁금해요")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("우리 집 인터넷 요금 얼마 나왔는지 확인해줘")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("우리 가족 회선 요금 얼마 나왔는지 조회해줘")).contains(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void detectsQuestionsAboutWhatTheAccountCurrentlyHolds() {
		assertThat(detect("지금 사용 중인 요금제 이름이 뭐예요?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("지금 이용 중인 요금제가 어떤 거죠?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("현재 가입된 부가서비스가 뭐뭐 있는지 알려줘")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내 계정에 등록된 카드가 뭐지?")).contains(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void doesNotFlagPriceScheduleOrPolicyQuestionsThatMentionPeriodsAndUsage() {
		assertThat(detect("이번 달에 가입하면 받을 수 있는 혜택이 뭔가요?")).isEmpty();
		assertThat(detect("이번 달 요금제 가격은 얼마인가요?")).isEmpty();
		assertThat(detect("이번 달 요금이 청구됐는데 납부 기한이 언제까지예요?")).isEmpty();
		assertThat(detect("지난달에 사용한 데이터가 이번 달로 이월되나요?")).isEmpty();
		assertThat(detect("남은 데이터를 다음 달로 이월할 수 있나요?")).isEmpty();
		assertThat(detect("문자 정액 상품을 이번 달에 다 못 썼는데 남은 건수를 다음 달에 쓸 수 있는지 문의드립니다.")).isEmpty();
		assertThat(detect("이번 달 안에 해지하면 위약금이 있나요?")).isEmpty();
		assertThat(detect("로밍 요금은 하루에 얼마인가요?")).isEmpty();
	}

	@Test
	void doesNotFlagQuestionsAboutChangingWhatTheAccountCurrentlyHolds() {
		assertThat(detect("현재 사용 중인 요금제를 변경하려면 어떻게 해야 하나요?")).isEmpty();
		assertThat(detect("지금 쓰는 요금제에서 데이터를 추가하는 방법이 있나요?")).isEmpty();
		assertThat(detect("지금 쓰는 요금제는 데이터가 모자라서 한 20기가 정도 주는 걸로 바꾸고 싶은데 뭐가 있어요?")).isEmpty();
		assertThat(detect("현재 가입 가능한 요금제를 알려주세요")).isEmpty();
		assertThat(detect("사용 중인 요금제의 약정을 확인하는 방법을 알려주세요")).isEmpty();
	}

	@Test
	void doesNotFlagQuestionsAskingWhetherARecordCanBeViewed() {
		// 기록을 볼 수 있는지 묻는 질문은 FAQ가 답한다("…내 인증 내역을 볼 수 있나요?"). 1인칭이 있어도 잡지 않는다.
		assertThat(detect("휴대폰번호 보호서비스에서 내 인증 내역을 볼 수 있나요?")).isEmpty();
		assertThat(detect("제 요금 납부 내역 좀 조회할 수 있을까요?")).isEmpty();
	}

	@Test
	void detectsGenericCompetitorWhenTheCurrentCarrierIsComparedWithThisService() {
		assertThat(detect("지금 쓰는 통신사에서 여기로 옮기면 더 이득인가요?")).contains(Rule.COMPETITOR_GENERIC);
		assertThat(detect("기존에 쓰던 통신사가 더 저렴한 것 같은데 어때요?")).contains(Rule.COMPETITOR_GENERIC);
		assertThat(detect("현재 쓰는 통신사랑 여기 중에 어디가 더 이득이에요?")).contains(Rule.COMPETITOR_GENERIC);
		assertThat(detect("예전에 쓰던 통신사랑 요금 차이가 얼마나 나요?")).contains(Rule.COMPETITOR_GENERIC);
	}

	@Test
	void doesNotFlagCurrentCarrierQuestionsWithoutComparison() {
		assertThat(detect("기존 통신사에서 번호이동 하려면 어떻게 해야 하나요?")).isEmpty();
		assertThat(detect("지금 쓰는 통신사에서 번호이동하려면 필요한 서류가 뭐예요?")).isEmpty();
		assertThat(detect("다른 통신사에서 쓰던 휴대폰도 사용할 수 있나요?")).isEmpty();
	}

	@Test
	void returnsEmptyForNullBlankOrPlainQuestions() {
		assertThat(detect(null)).isEmpty();
		assertThat(detect("  ")).isEmpty();
		assertThat(detect("자동이체 계좌를 변경하고 싶어요")).isEmpty();
	}

	@Test
	void doesNotTreatASelfIntroductionBeforeARequestAsPersonalLookup() {
		// 사용자가 실제로 겪은 질문: "나는"이 1인칭으로, "궁금"이 조회 표현으로 잡혀 검색 결과가 전부 비워졌다.
		assertThat(detect("어떤 요금제가 있는지 궁금해. 나는 대학생이고, 데이터를 많이 사용해")).isEmpty();
		assertThat(detect("나는 대학생이고, 데이터를 많이 사용해 어떤 요금제가 있는지 궁금해.")).isEmpty();
		assertThat(detect("나는 대학생이고 데이터를 많이 사용하는데 어떤 요금제가 있어?")).isEmpty();
		assertThat(detect("나는 대학생이야. 요금제 알려줘")).isEmpty();
		assertThat(detect("저는 군인인데 할인되는 요금제 알려주세요")).isEmpty();
		assertThat(detect("저는 60대인데 시니어 요금제 알려주세요")).isEmpty();
		assertThat(detect("제가 가족이 4명인데 결합 할인 알려주세요")).isEmpty();
		assertThat(detect("저는 해외여행을 자주 가는데 로밍 요금 알려주세요")).isEmpty();
	}

	@Test
	void doesNotTreatPriceOrTimingWordsAfterASelfIntroductionAsPersonalLookup() {
		// "얼마", "언제"는 가격·가입 시점을 묻는 말에도 쓰인다.
		assertThat(detect("저는 영상을 많이 보는데 월 얼마짜리 요금제가 좋아요?")).isEmpty();
		assertThat(detect("나는 대학생인데 제일 싼 요금제는 얼마야?")).isEmpty();
		assertThat(detect("저는 대학생인데 언제 가입하면 할인돼?")).doesNotContain(Rule.PERSONAL_LOOKUP);
		assertThat(detect("나는 직장인인데 요금제 추천해줘")).isEmpty();
	}

	@Test
	void stillDetectsPersonalLookupWrittenWithASubjectFirstPerson() {
		assertThat(detect("제가 쓰는 요금제 알려주세요")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("제가 가입한 부가서비스 알려주세요")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("저는 이번 달에 얼마 냈나요?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("나는 지금 데이터 얼마 남았어?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("저는 언제 가입했는지 궁금해요")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내가 지난달에 쓴 통화량 알려줘")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내가 마지막으로 결제한 날짜가 언제였지?")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("내가 예전에 문의한 내역 보여줘")).contains(Rule.PERSONAL_LOOKUP);
	}

	@Test
	void stillDetectsPossessiveFirstPersonWithOnlyAGenericRequestWord() {
		// "내/제 + 대상"은 본인 소유를 직접 가리키므로 "알려줘"만 있어도 본인 기록 조회다.
		assertThat(detect("내 요금 알려줘")).contains(Rule.PERSONAL_LOOKUP);
		assertThat(detect("제 데이터 사용 현황이 궁금해요")).contains(Rule.PERSONAL_LOOKUP);
	}
}
