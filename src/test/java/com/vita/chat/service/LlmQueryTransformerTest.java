package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.vita.chat.client.BedrockChatClient;
import com.vita.chat.dto.CompletionResult;
import com.vita.chat.service.QueryTransformer.TransformOutcome;
import com.vita.search.pipeline.TransformInfo;

/** 질문 변환기가 trace에 남길 모델 호출 기록(프롬프트, 응답 원문, 토큰)을 결과와 함께 돌려주는지 확인한다. */
class LlmQueryTransformerTest {

    private final BedrockChatClient client = mock(BedrockChatClient.class);

    private LlmQueryTransformer transformer(boolean enabled) {
        LlmQueryTransformer transformer = new LlmQueryTransformer(client);
        ReflectionTestUtils.setField(transformer, "enabled", enabled);
        return transformer;
    }

    @Test
    void returnsPromptRawResponseAndTokensWithTheParsedResult() {
        String raw = "{\"faq_query\":\"로밍 요금\",\"plan_query\":\"로밍 요금제\"}";
        when(client.completeWithUsage(anyString(), anyString())).thenReturn(new CompletionResult(raw, 120L, 30L, 150L));

        TransformOutcome outcome = transformer(true).transformWithStatus("로밍 얼마예요?", "(이전 대화)");

        assertThat(outcome.result().faqQuery()).isEqualTo("로밍 요금");
        assertThat(outcome.info().kind()).isEqualTo(TransformInfo.Kind.CHANGED);
        assertThat(outcome.trace().rawResponse()).isEqualTo(raw);
        assertThat(outcome.trace().userPrompt()).contains("로밍 얼마예요?").contains("(이전 대화)");
        assertThat(outcome.trace().promptTokens()).isEqualTo(120L);
        assertThat(outcome.trace().completionTokens()).isEqualTo(30L);
        assertThat(outcome.trace().totalTokens()).isEqualTo(150L);
    }

    @Test
    void keepsThePromptEvenWhenTheModelCallFails() {
        when(client.completeWithUsage(anyString(), anyString())).thenThrow(new IllegalStateException("timeout"));

        TransformOutcome outcome = transformer(true).transformWithStatus("요금제 알려줘", null);

        assertThat(outcome.info().reason()).isEqualTo(TransformInfo.REASON_CALL_FAILED);
        assertThat(outcome.result().faqQuery()).isEqualTo("요금제 알려줘");
        assertThat(outcome.trace().userPrompt()).contains("요금제 알려줘").contains("(없음)");
        assertThat(outcome.trace().rawResponse()).isNull();
        assertThat(outcome.trace().totalTokens()).isNull();
    }

    @Test
    void keepsTheRawResponseWhenItHasNoJson() {
        when(client.completeWithUsage(anyString(), anyString())).thenReturn(new CompletionResult("죄송합니다", 10L, 2L, 12L));

        TransformOutcome outcome = transformer(true).transformWithStatus("질문", null);

        assertThat(outcome.info().reason()).isEqualTo(TransformInfo.REASON_NO_JSON);
        assertThat(outcome.trace().rawResponse()).isEqualTo("죄송합니다");
        assertThat(outcome.trace().totalTokens()).isEqualTo(12L);
    }

    @Test
    void hasNoTraceWhenTheTransformIsDisabledBecauseNoModelWasCalled() {
        TransformOutcome outcome = transformer(false).transformWithStatus("질문", null);

        assertThat(outcome.info().reason()).isEqualTo(TransformInfo.REASON_DISABLED);
        assertThat(outcome.trace()).isNull();
        verify(client, never()).completeWithUsage(anyString(), anyString());
    }
}
