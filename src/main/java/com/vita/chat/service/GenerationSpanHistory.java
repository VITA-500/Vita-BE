package com.vita.chat.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.vita.chat.dto.StreamResult;

/**
 * 답변 생성 단계(generation span)의 history를 만든다. 모델에 실제로 보낸 프롬프트와 받은 답변, 토큰 사용량을 남겨
 * "이 context로 왜 이 답이 나왔나"를 나중에 다시 볼 수 있게 한다.
 */
final class GenerationSpanHistory {

    private GenerationSpanHistory() {
    }

    /** 답변 조각이 오는 동안의 진행 상황. 첫 조각이 오기까지 걸린 시간과 조각 수를 센다. */
    static final class Progress {

        private final long startedNanos = System.nanoTime();
        private int deltaCount;
        private Long firstDeltaMillis;

        /** 답변 조각이 하나 올 때마다 부른다. 스트림 스레드 하나에서만 불린다. */
        void onDelta() {
            if (firstDeltaMillis == null) {
                firstDeltaMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            }
            deltaCount++;
        }
    }

    /**
     * @param question    원문 질문
     * @param context     모델에 준 context(검색·조회 결과를 합친 문자열)
     * @param history     모델에 함께 준 대화 이력(없으면 null)
     * @param answer      지금까지 받은 답변(오류로 끊겼으면 일부만)
     * @param progress    첫 조각까지 걸린 시간과 조각 수
     * @param streamed    스트림 호출 기록. 오류로 끊겼거나 구현체가 기록을 주지 않으면 null
     */
    static Map<String, Object> of(String question, String context, String history, String answer, Progress progress,
            StreamResult streamed) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("question", question);
        map.put("conversation_history", history);
        // 프롬프트 전문에 context가 이미 들어 있으므로, 전문이 있으면 context를 따로 또 저장하지 않는다(용량).
        if (streamed != null && streamed.userPrompt() != null) {
            map.put("user_prompt", streamed.userPrompt());
        } else {
            map.put("context", context);
        }
        map.put("answer", answer);
        map.put("delta_count", progress.deltaCount);
        map.put("first_delta_ms", progress.firstDeltaMillis);
        if (streamed != null) {
            map.put("model", streamed.model());
            map.put("prompt_tokens", streamed.promptTokens());
            map.put("completion_tokens", streamed.completionTokens());
            map.put("total_tokens", streamed.totalTokens());
        }
        return map;
    }
}
