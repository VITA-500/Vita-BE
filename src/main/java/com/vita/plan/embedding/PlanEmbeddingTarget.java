package com.vita.plan.embedding;

/** 요금제 v1은 description 전체를 문서 임베딩한다. FAQ 정책 버전과 독립적이다. */
public record PlanEmbeddingTarget(long id, String description) {
    public static final String VERSION = "v1";
}
