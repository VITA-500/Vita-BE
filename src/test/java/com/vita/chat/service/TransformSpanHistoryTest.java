package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.chat.dto.DataRange;
import com.vita.chat.dto.PlanIntent;
import com.vita.chat.dto.PriceRange;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.chat.dto.StoreIntent;
import com.vita.chat.service.QueryTransformer.TransformOutcome;
import com.vita.chat.service.QueryTransformer.TransformTrace;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.service.PlanSortKey;

/** transform span의 history에 원문·모델 호출 기록·변환 결과가 담기고, 그대로 JSON으로 바뀌는지 확인한다. */
class TransformSpanHistoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void containsQuestionModelCallAndParsedResult() throws Exception {
        QueryTransformResult result = new QueryTransformResult("FAQ 질의", "요금제 질의",
                new PlanIntent(true, PlanSortKey.CHEAPEST, 2), true,
                new PriceRange(30000L, 39999L), new DataRange(1024L, null),
                new StoreIntent(StoreIntent.Type.PHONE, null, null, List.of("개통"), true,
                        LocalTime.of(18, 30), "방학역", null));
        TransformOutcome outcome = new TransformOutcome(result, TransformInfo.changed(),
                new TransformTrace("프롬프트", "{\"a\":1}", 100L, 20L, 120L));

        Map<String, Object> history = TransformSpanHistory.of("원문 질문", "이전 대화", outcome);

        assertThat(history).containsEntry("question", "원문 질문")
                .containsEntry("conversation_history", "이전 대화")
                .containsEntry("outcome", "CHANGED")
                .containsEntry("user_prompt", "프롬프트")
                .containsEntry("raw_response", "{\"a\":1}")
                .containsEntry("total_tokens", 120L);
        assertThat(history.get("fallback_reason")).isNull();
        Map<String, Object> r = (Map<String, Object>) history.get("result");
        assertThat(r).containsEntry("faq_query", "FAQ 질의").containsEntry("plan_query", "요금제 질의")
                .containsEntry("is_extreme", true).containsEntry("sort_key", "CHEAPEST")
                .containsEntry("max_price_won", 39999L).containsEntry("min_data_mb", 1024L);
        Map<String, Object> store = (Map<String, Object>) r.get("store_intent");
        assertThat(store).containsEntry("type", "PHONE").containsEntry("open_at", "18:30")
                .containsEntry("place", "방학역");
        // 기본 ObjectMapper로도 그대로 직렬화돼야 저장 단계에서 표시용 JSON으로 대체되지 않는다.
        assertThat(new ObjectMapper().writeValueAsString(history)).contains("\"open_at\":\"18:30\"");
    }

    @Test
    void recordsFallbackReasonAndOmitsModelCallWhenNoModelWasCalled() {
        TransformOutcome outcome = new TransformOutcome(QueryTransformResult.original("질문"),
                TransformInfo.fallback(TransformInfo.REASON_DISABLED));

        Map<String, Object> history = TransformSpanHistory.of("질문", null, outcome);

        assertThat(history).containsEntry("outcome", "FALLBACK_ORIGINAL")
                .containsEntry("fallback_reason", "DISABLED")
                .doesNotContainKeys("user_prompt", "raw_response", "total_tokens");
        assertThat(history.get("conversation_history")).isNull();
    }
}
