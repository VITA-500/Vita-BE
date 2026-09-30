package com.vita.search.regression;

/**
 * 규칙 기반 무관 질문 판별({@code IrrelevantQueryDetector})의 검증용 질문 한 건. 규칙을 만들 때 보지 않은
 * 문항으로 일반화 성능을 확인하기 위한 세트이다.
 *
 * @param kind  기대 유형. PERSONAL(개인 정보 조회), COMPETITOR(타사 질문)는 규칙에 걸려야 하고,
 *              NORMAL(1인칭·타사 언급이 있는 정상 질문)은 걸리면 안 된다.
 * @param query 질문 원문
 */
public record RuleValidationQuery(String kind, String query) {
}
