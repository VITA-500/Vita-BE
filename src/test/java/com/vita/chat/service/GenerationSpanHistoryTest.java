package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.chat.dto.StreamResult;

/** generation span의 history가 프롬프트·답변·토큰을 담고, 프롬프트 전문이 있으면 context를 중복 저장하지 않는지 확인한다. */
class GenerationSpanHistoryTest {

    private static GenerationSpanHistory.Progress progressWith(int deltas) {
        GenerationSpanHistory.Progress progress = new GenerationSpanHistory.Progress();
        for (int i = 0; i < deltas; i++) {
            progress.onDelta();
        }
        return progress;
    }

    @Test
    void usesThePromptAndTokensFromTheStreamResultAndDropsTheSeparateContext() throws Exception {
        StreamResult streamed = new StreamResult("조립된 프롬프트", "model-x", 100L, 20L, 120L, "시스템 프롬프트");

        Map<String, Object> history = GenerationSpanHistory.of("질문", "context 본문", "이전 대화", "답변", progressWith(3), streamed, "LOW_SIMILARITY", 0.72);

        assertThat(history).containsEntry("question", "질문")
                .containsEntry("conversation_history", "이전 대화")
                .containsEntry("user_prompt", "조립된 프롬프트")
                .containsEntry("answer", "답변")
                .containsEntry("delta_count", 3)
                .containsEntry("model", "model-x")
                .containsEntry("prompt_tokens", 100L)
                .containsEntry("completion_tokens", 20L)
                .containsEntry("total_tokens", 120L)
                .containsEntry("system_prompt", "시스템 프롬프트")
                .containsEntry("unresolved_reason", "LOW_SIMILARITY")
                .containsEntry("top_similarity", 0.72)
                .doesNotContainKey("context");
        assertThat(history.get("first_delta_ms")).isNotNull();
        assertThat(new ObjectMapper().writeValueAsString(history)).contains("\"answer\":\"답변\"");
    }

    @Test
    void keepsTheContextWhenThereIsNoPromptRecord() {
        Map<String, Object> history = GenerationSpanHistory.of("질문", "context 본문", null, "", progressWith(0), null, null, null);

        assertThat(history).containsEntry("context", "context 본문")
                .containsEntry("delta_count", 0)
                .doesNotContainKeys("user_prompt", "total_tokens", "model");
        assertThat(history.get("first_delta_ms")).isNull();
    }
}
