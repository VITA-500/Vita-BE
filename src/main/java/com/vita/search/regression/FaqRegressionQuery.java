package com.vita.search.regression;

/** FAQ 검색 정확도 회귀 테스트용 질문 한 건. category/subcategory가 이 질문의 정답 세부분류다. */
public record FaqRegressionQuery(String category, String subcategory, String style, String query) {
}
