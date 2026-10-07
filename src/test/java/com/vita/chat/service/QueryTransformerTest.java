package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vita.chat.client.BedrockChatClient;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.search.pipeline.TransformInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class QueryTransformerTest {

    private static final String QUESTION = "로밍 요금 알려줘";

    private BedrockChatClient client;
    private QueryTransformer transformer;

    @BeforeEach
    void setUp() {
        client = mock(BedrockChatClient.class);
        transformer = new QueryTransformer(client);
        ReflectionTestUtils.setField(transformer, "enabled", true);
    }

    @Test
    void disabledFallsBackToTheOriginalQuestion() {
        ReflectionTestUtils.setField(transformer, "enabled", false);

        QueryTransformer.TransformOutcome outcome = transformer.transformWithStatus(QUESTION, null);

        assertThat(outcome.info()).isEqualTo(TransformInfo.fallback(TransformInfo.REASON_DISABLED));
        assertThat(outcome.result().faqQuery()).isEqualTo(QUESTION);
        assertThat(outcome.result().planQuery()).isEqualTo(QUESTION);
    }

    @Test
    void failedModelCallFallsBackAndRecordsTheReason() {
        when(client.complete(anyString(), anyString())).thenThrow(new IllegalStateException("timeout"));

        QueryTransformer.TransformOutcome outcome = transformer.transformWithStatus(QUESTION, null);

        assertThat(outcome.info().kind()).isEqualTo(TransformInfo.Kind.FALLBACK_ORIGINAL);
        assertThat(outcome.info().reason()).isEqualTo(TransformInfo.REASON_CALL_FAILED);
        assertThat(outcome.result().faqQuery()).isEqualTo(QUESTION);
    }

    @Test
    void nullOrJsonlessResponseIsNoJson() {
        when(client.complete(anyString(), anyString())).thenReturn(null);
        assertThat(transformer.transformWithStatus(QUESTION, null).info().reason()).isEqualTo(TransformInfo.REASON_NO_JSON);

        when(client.complete(anyString(), anyString())).thenReturn("변환할 수 없습니다");
        assertThat(transformer.transformWithStatus(QUESTION, null).info().reason()).isEqualTo(TransformInfo.REASON_NO_JSON);

        // 닫는 중괄호가 없는 응답도 JSON이 없는 것으로 본다.
        when(client.complete(anyString(), anyString())).thenReturn("{\"faq_query\": ");
        assertThat(transformer.transformWithStatus(QUESTION, null).info().reason()).isEqualTo(TransformInfo.REASON_NO_JSON);
    }

    @Test
    void unparsableJsonIsParseFailed() {
        when(client.complete(anyString(), anyString())).thenReturn("{faq_query: 로밍}");

        QueryTransformer.TransformOutcome outcome = transformer.transformWithStatus(QUESTION, null);

        assertThat(outcome.info().reason()).isEqualTo(TransformInfo.REASON_PARSE_FAILED);
        assertThat(outcome.result().faqQuery()).isEqualTo(QUESTION);
    }

    @Test
    void bothQueriesNullOrBlankFallsBackWithBothNull() {
        when(client.complete(anyString(), anyString())).thenReturn("{\"faq_query\": null, \"plan_query\": \"  \"}");

        QueryTransformer.TransformOutcome outcome = transformer.transformWithStatus(QUESTION, null);

        assertThat(outcome.info()).isEqualTo(TransformInfo.fallback(TransformInfo.REASON_BOTH_NULL));
        assertThat(outcome.result().faqQuery()).isEqualTo(QUESTION);
        assertThat(outcome.result().planQuery()).isEqualTo(QUESTION);
    }

    @Test
    void oneSidedNullIsPartialAndKeepsTheNullForTheCaller() {
        when(client.complete(anyString(), anyString())).thenReturn("{\"faq_query\": \"로밍 요금\", \"plan_query\": null}");

        QueryTransformer.TransformOutcome outcome = transformer.transformWithStatus(QUESTION, null);

        assertThat(outcome.info()).isEqualTo(TransformInfo.partialNull(false, true));
        assertThat(outcome.result().faqQuery()).isEqualTo("로밍 요금");
        assertThat(outcome.result().planQuery()).isNull();
    }

    @Test
    void bothQueriesPresentIsChanged() {
        when(client.complete(anyString(), anyString()))
                .thenReturn("설명 {\"faq_query\": \"해외 로밍 요금\", \"plan_query\": \"로밍 요금제\"} 끝");

        QueryTransformer.TransformOutcome outcome = transformer.transformWithStatus(QUESTION, null);

        assertThat(outcome.info()).isEqualTo(TransformInfo.changed());
        assertThat(outcome.result()).isEqualTo(new QueryTransformResult("해외 로밍 요금", "로밍 요금제"));
    }

    @Test
    void transformStillReturnsOnlyTheResult() {
        when(client.complete(anyString(), anyString())).thenReturn("{\"faq_query\": \"a\", \"plan_query\": \"b\"}");

        assertThat(transformer.transform(QUESTION, null)).isEqualTo(new QueryTransformResult("a", "b"));
    }
}
