package com.vita.chat.dto;

public record QueryTransformResult(
        String faqQuery,
        String planQuery,
        PlanIntent planIntent,
        boolean structured,
        PriceRange priceRange) {

    // 기존 호출부(인자 4개) 호환
    public QueryTransformResult(String faqQuery, String planQuery, PlanIntent planIntent, boolean structured) {
        this(faqQuery, planQuery, planIntent, structured, PriceRange.none());
    }

    public static QueryTransformResult original(String question) {
        return new QueryTransformResult(question, question, PlanIntent.none(), false);
    }
}