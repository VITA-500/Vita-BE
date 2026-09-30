package com.vita.search.regression;

/**
 * 요금제 검색 정확도 회귀 테스트용 질문 한 건.
 *
 * @param planCode 이 질문을 만들 때 염두에 둔 요금제(정답 1개로 고정해서 채점하는 엄격 기준용)
 * @param expect   이 질문을 만족하는 요금제의 조건(조건 충족 기준용). 질문이 모호해서 정할 수 없으면 null이고,
 *                 이 경우 조건 충족 채점에서 제외된다.
 */
public record PlanRegressionQuery(String planCode, String aspect, String query, PlanExpectation expect) {

	/**
	 * 요금제가 만족해야 하는 조건. 값이 채워진 조건을 전부 만족하는 요금제가 모두 정답이다.
	 * 예: {targetGroup=SENIOR}이면 시니어 요금제 전체, {monthlyFee=31000}이면 월 31,000원 요금제.
	 */
	public record PlanExpectation(String planCode, String targetGroup, Integer monthlyFee, Long dataMb,
			String dataPolicy, String voicePolicy, String smsPolicy) {
	}
}
