package com.vita.search.pipeline;

/**
 * 검색 파이프라인 후처리에 쓰는 설정값. application.yml의 값을 한곳에 모아서, 서비스와 평가 러너가 같은 값을 쓰게 한다.
 *
 * @param faqThreshold         "관련 FAQ 없음"으로 처리할 코사인 유사도 하한선({@code retrieval.similarity-threshold}).
 *                             FAQ가 질문/답변 형태라 요금제와 분포가 달라 따로 둔다.
 * @param planThreshold        "관련 요금제 없음"으로 처리할 유사도 하한선({@code plan.retrieval.similarity-threshold}).
 *                             조건 매칭으로 좁혀진 요금제에는 적용하지 않는다.
 * @param irrelevantRuleEnabled 규칙(정규식)으로 개인 정보 조회·타사 질문을 알아보고 컨텍스트를 비울지
 *                             ({@code retrieval.irrelevant-rule.enabled}). 오탐이 보이면 false로 즉시 끌 수 있다.
 * @param categoryBoostBonus   질문에 분류 이름 단어가 있을 때 그 분류 후보에 더하는 순위용 가산점
 *                             ({@code retrieval.category-boost.bonus}). 순위에만 쓰이고 유사도 값은 바뀌지 않는다. 0이면 꺼진다.
 */
public record RetrievalSettings(
		double faqThreshold,
		double planThreshold,
		boolean irrelevantRuleEnabled,
		double categoryBoostBonus) {
}
