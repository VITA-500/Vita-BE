package com.vita.search.regression;

/** 검색과 무관해야 하는 질문 한 건(오프토픽/인사말/타사 언급/도메인 내 미커버/개인화 정보). */
public record NegativeRegressionQuery(String topic, String query) {
}
