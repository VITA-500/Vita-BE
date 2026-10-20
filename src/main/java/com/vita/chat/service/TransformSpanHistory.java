package com.vita.chat.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.vita.chat.dto.QueryTransformResult;
import com.vita.chat.dto.StoreIntent;
import com.vita.chat.service.QueryTransformer.TransformOutcome;
import com.vita.chat.service.QueryTransformer.TransformTrace;
import com.vita.search.pipeline.TransformInfo;

/**
 * 질문 변환 단계(transform span)의 history를 만든다. 값은 모두 문자열·숫자·불리언·Map으로 바꿔서 담아,
 * 나중에 DTO 모양이 바뀌어도 이미 저장된 기록의 형태가 흔들리지 않게 한다.
 */
final class TransformSpanHistory {

    private TransformSpanHistory() {
    }

    /**
     * @param question  사용자가 보낸 원문 질문
     * @param history   변환 모델에 함께 준 대화 이력(없으면 null)
     * @param outcome   변환 결과와 모델 호출 기록
     */
    static Map<String, Object> of(String question, String history, TransformOutcome outcome) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("question", question);
        map.put("conversation_history", history);

        TransformInfo info = outcome.info();
        map.put("outcome", info.kind().name());
        map.put("fallback_reason", info.reason().isEmpty() ? null : info.reason());

        TransformTrace trace = outcome.trace();
        if (trace != null) {
            map.put("user_prompt", trace.userPrompt());
            map.put("raw_response", trace.rawResponse());
            map.put("prompt_tokens", trace.promptTokens());
            map.put("completion_tokens", trace.completionTokens());
            map.put("total_tokens", trace.totalTokens());
        }

        QueryTransformResult r = outcome.result();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("faq_query", r.faqQuery());
        result.put("plan_query", r.planQuery());
        result.put("faq_sub_queries", r.faqSubQueries());
        result.put("is_extreme", r.planIntent().extreme());
        result.put("sort_key", r.planIntent().sortKey() == null ? null : r.planIntent().sortKey().name());
        result.put("limit", r.planIntent().limit());
        result.put("is_structured", r.structured());
        result.put("min_price_won", r.priceRange().minWon());
        result.put("max_price_won", r.priceRange().maxWon());
        result.put("min_data_mb", r.dataRange().minMb());
        result.put("max_data_mb", r.dataRange().maxMb());
        result.put("store_intent", storeIntent(r.storeIntent()));
        map.put("result", result);
        return map;
    }

    /** 매장 조회가 실패했을 때 남길 최소 history(매장 의도만). */
    static Map<String, Object> storeIntentOnly(StoreIntent s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("store_intent", storeIntent(s));
        return map;
    }

    /** 매장 의도를 history용 Map으로 바꾼다. 변환 단계와 매장 조회 단계가 같은 모양을 쓴다. */
    static Map<String, Object> storeIntent(StoreIntent s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", s.type().name());
        map.put("category", s.category());
        map.put("brand", s.brand());
        map.put("services", s.services());
        map.put("open_now", s.openNow());
        map.put("open_at", s.openAt() == null ? null : s.openAt().toString());
        map.put("place", s.place());
        map.put("store_name", s.storeName());
        map.put("other_places", s.otherPlaces());
        map.put("min_km", s.minKm());
        map.put("max_km", s.maxKm());
        return map;
    }
}
