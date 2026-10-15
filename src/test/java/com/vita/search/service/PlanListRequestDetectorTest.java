package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlanListRequestDetectorTest {

	@Test
	void detectsRequestsForTheWholePlanList() {
		assertThat(PlanListRequestDetector.matches("전체 요금제 알려줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("요금제 전체 알려줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("모든 요금제 알려줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("요금제 전부 알려줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("요금제 다 알려줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("비타 전체 요금제 보여줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("모든 VITA 요금제 설명해줘")).isTrue();
	}

	@Test
	void detectsRequestsWhereAllIsFollowedByDa() {
		assertThat(PlanListRequestDetector.matches("요금제 전부 다 알려줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("요금제 모두 다 보여주세요")).isTrue();
		assertThat(PlanListRequestDetector.matches("요금제 전체 다 알려줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("비타 요금제 전부 다 설명해줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("전부 다 요금제 알려줘")).isTrue();
	}

	@Test
	void detectsListNounsAndWhatExistsQuestions() {
		assertThat(PlanListRequestDetector.matches("요금제 목록 보여줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("VITA 요금제 종류 알려줘")).isTrue();
		assertThat(PlanListRequestDetector.matches("플랜 목록 알려주세요")).isTrue();
		assertThat(PlanListRequestDetector.matches("요금제 뭐뭐 있어?")).isTrue();
		assertThat(PlanListRequestDetector.matches("요금제 뭐 있어요?")).isTrue();
		assertThat(PlanListRequestDetector.matches("어떤 요금제가 있나요?")).isTrue();
		assertThat(PlanListRequestDetector.matches("VITA에는 어떤 요금제가 있나요?")).isTrue();
	}

	@Test
	void detectsTransformedPlanQueriesWithLabels() {
		assertThat(PlanListRequestDetector.matches("조건: 전체 요금제\n질문: 전체 요금제를 알려주세요")).isTrue();
		assertThat(PlanListRequestDetector.matches("질문: 요금제 전체 목록을 알려주세요\n핵심 키워드: 요금제, 목록, 전체")).isTrue();
	}

	@Test
	void doesNotTreatWordsLikeAllUsedUpAsListRequests() {
		assertThat(PlanListRequestDetector.matches("요금제 데이터 다 쓰면 어떻게 돼요?")).isFalse();
		assertThat(PlanListRequestDetector.matches("데이터 다 쓰고 나면 속도가 어떻게 되나요?")).isFalse();
		assertThat(PlanListRequestDetector.matches("전체 데이터 사용량 확인하고 싶어요")).isFalse();
		assertThat(PlanListRequestDetector.matches("요금제 다 내면 언제 정지돼?")).isFalse();
		assertThat(PlanListRequestDetector.matches("요금제 전부 다 쓰면 어떻게 돼요?")).isFalse();
		assertThat(PlanListRequestDetector.matches("요금제 데이터 전부 다 쓰고 나면 속도가 어떻게 돼요?")).isFalse();
	}

	@Test
	void doesNotTreatActionOrSuperlativeQuestionsAsListRequests() {
		assertThat(PlanListRequestDetector.matches("전체 요금제 해지하고 싶어요")).isFalse();
		assertThat(PlanListRequestDetector.matches("요금제 변경하고 싶어요")).isFalse();
		assertThat(PlanListRequestDetector.matches("요금제를 바꾸면 위약금이 있어요?")).isFalse();
		assertThat(PlanListRequestDetector.matches("가장 싼 요금제 알려줘")).isFalse();
		assertThat(PlanListRequestDetector.matches("요금제 가입 방법 알려줘")).isFalse();
	}

	@Test
	void doesNotMatchQuestionsAboutAllPlansThatAreNotRequestsForTheList() {
		assertThat(PlanListRequestDetector.matches("모든 요금제에 부가세가 포함되어 있나요?")).isFalse();
		assertThat(PlanListRequestDetector.matches("모든 요금제에서 5G 쓸 수 있어요?")).isFalse();
		assertThat(PlanListRequestDetector.matches("요금제 추천해줘")).isFalse();
		assertThat(PlanListRequestDetector.matches("제휴 혜택 전부 알려줘")).isFalse();
	}

	@Test
	void returnsFalseForNullOrBlank() {
		assertThat(PlanListRequestDetector.matches(null)).isFalse();
		assertThat(PlanListRequestDetector.matches("   ")).isFalse();
	}
}
