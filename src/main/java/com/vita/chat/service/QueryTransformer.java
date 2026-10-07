package com.vita.chat.service;

import com.vita.chat.dto.QueryTransformResult;
import com.vita.search.pipeline.TransformInfo;

/**
 * 사용자 질문을 FAQ 검색용 / 요금제 검색용 쿼리로 변환하는 모듈.
 * 구현체를 바꿔 끼울 수 있고, 평가(BE3)와 서비스(BE4)가 같은 모듈을 쓴다.
 */
public interface QueryTransformer {

    /**
     * 변환 결과와, 그 결과가 어떻게 나왔는지의 기록(변환됨 / 한쪽만 변환 / 원문으로 폴백과 그 사유).
     * 기록은 평가에서 폴백을 세는 데 쓰고, 검색 동작에는 영향을 주지 않는다.
     */
    record TransformOutcome(QueryTransformResult result, TransformInfo info) {
    }

    /**
     * 질문을 변환하고, 폴백이 일어났다면 그 사유를 함께 돌려준다.
     * 실패해도 예외를 던지지 않고 원문으로 양쪽을 검색하도록 폴백한다.
     */
    TransformOutcome transformWithStatus(String question, String conversationHistory);

    /** 변환 결과만 필요한 호출자(서비스)용. 폴백 동작은 {@link #transformWithStatus}와 같다. */
    default QueryTransformResult transform(String question, String conversationHistory) {
        return transformWithStatus(question, conversationHistory).result();
    }
}