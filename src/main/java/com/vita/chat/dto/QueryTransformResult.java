package com.vita.chat.dto;

public record QueryTransformResult(
        String faqQuery,
        String planQuery,
        PlanIntent planIntent,   // 극값 여부, sortKey, limit (기존 분류기 결과와 같은 타입)
        boolean structured) {    // 정형 질문 여부 (값만 추출, 아직 쓰는 곳 없음)

    public static QueryTransformResult original(String question) {
        return new QueryTransformResult(question, question, PlanIntent.none(), false);
    }
}