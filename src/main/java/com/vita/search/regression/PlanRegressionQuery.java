package com.vita.search.regression;

/** 요금제 검색 정확도 회귀 테스트용 질문 한 건. planCode가 이 질문의 정답 요금제다. */
public record PlanRegressionQuery(String planCode, String aspect, String query) {
}
