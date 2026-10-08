package com.vita.chat.service;

/**
 * 한국어 사용자 질문을 영어로 "번역만" 하는 모듈 (실험 B).
 * {@link QueryTransformer}와 달리 질문을 FAQ용/요금제용으로 바꾸거나 의미를 보강하지 않는다.
 * 구현체를 바꿔 끼울 수 있고, 평가(BE3)와 서비스(BE4)가 같은 모듈을 쓴다.
 */
public interface QuestionTranslator {

    /**
     * 번역 결과와, 폴백이 일어났는지의 기록. 폴백이면 translated에는 원문이 들어 있다.
     *
     * @param fallbackReason 정상 번역이면 null
     */
    record TranslationOutcome(String original, String translated, boolean fallback, String fallbackReason) {
    }

    /**
     * 질문을 번역하고, 폴백이 일어났다면 그 사유를 함께 돌려준다.
     * 실패해도 예외를 던지지 않고 원문으로 폴백한다.
     */
    TranslationOutcome translateWithStatus(String question);

    /** 번역문만 필요한 호출자용. 폴백 동작은 {@link #translateWithStatus}와 같다. */
    default String translate(String question) {
        return translateWithStatus(question).translated();
    }
}