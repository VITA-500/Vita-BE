package com.vita.chat.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.chat.client.BedrockChatClient;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.search.pipeline.TransformInfo;
import com.vita.chat.dto.PriceRange;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.vita.chat.dto.PlanIntent;
import com.vita.search.service.PlanSortKey;

/** 사용자 질문을 FAQ 검색용 / 요금제 검색용 쿼리로 변환한다. 실패하면 원문으로 폴백한다. */
@Slf4j
@Component
public class QueryTransformer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private static final int MAX_LIMIT = 3;

    private final BedrockChatClient bedrockChatClient;
    private final String systemPrompt;

    public QueryTransformer(BedrockChatClient bedrockChatClient) {
        this.bedrockChatClient = bedrockChatClient;
        this.systemPrompt = loadPrompt("prompts/query-transform.txt");
    }

    @Value("${query-transform.enabled:true}")
    private boolean enabled;

    /**
     * 변환 결과와, 그 결과가 어떻게 나왔는지의 기록(변환됨 / 한쪽만 변환 / 원문으로 폴백과 그 사유).
     * 기록은 평가에서 폴백을 세는 데 쓰고, 검색 동작에는 영향을 주지 않는다.
     */
    public record TransformOutcome(QueryTransformResult result, TransformInfo info) {
    }

    /** 변환 결과만 필요한 호출자(서비스)용. 폴백 동작은 {@link #transformWithStatus}와 같다. */
    public QueryTransformResult transform(String question, String conversationHistory) {
        return transformWithStatus(question, conversationHistory).result();
    }

    /**
     * 질문을 변환하고, 폴백이 일어났다면 그 사유를 함께 돌려준다. 폴백 조건은 다음과 같고 모두 원문으로 양쪽을 검색한다.
     * 비활성(DISABLED), 모델 호출 실패(CALL_FAILED), 응답에 JSON 없음(NO_JSON), JSON 파싱 실패(PARSE_FAILED),
     * 두 쿼리가 모두 비어 있음(BOTH_NULL).
     */
    public TransformOutcome transformWithStatus(String question, String conversationHistory) {

        if (!enabled) {
            return fallback(question, TransformInfo.REASON_DISABLED);
        }

        String raw;
        try {
            String history = (conversationHistory == null || conversationHistory.isBlank())
                    ? "(없음)" : conversationHistory;
            String userPrompt = """
                    <history>
                    %s
                    </history>
                    <user_question>
                    %s
                    </user_question>
                    """.formatted(history, question);

            raw = bedrockChatClient.complete(systemPrompt, userPrompt);
        } catch (Exception e) {
            log.warn("질문 변환 호출 실패, 원문 사용 - question={}", question, e);
            return fallback(question, TransformInfo.REASON_CALL_FAILED);
        }

        if (raw == null) {
            log.warn("질문 변환 응답이 비어 있어 원문 사용 - question={}", question);
            return fallback(question, TransformInfo.REASON_NO_JSON);
        }

        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            log.warn("질문 변환 응답에 JSON 없음, 원문 사용 - raw={}", raw);
            return fallback(question, TransformInfo.REASON_NO_JSON);
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("질문 변환 응답 파싱 실패, 원문 사용 - question={}", question, e);
            return fallback(question, TransformInfo.REASON_PARSE_FAILED);
        }

        String faqQuery = blankToNull(textOrNull(node, "faq_query"));
        String planQuery = blankToNull(textOrNull(node, "plan_query"));
        PlanIntent intent = parseIntent(node);
        boolean structured = node.path("is_structured").asBoolean(false);
        PriceRange priceRange = parsePriceRange(node);

        // 둘 다 null이면 "무관"이 아니라 변환 실패일 수 있으니 원문으로 검색 (단, 극값 질문이면 그대로 진행)
        if (faqQuery == null && planQuery == null && !intent.extreme()) {
            return fallback(question, TransformInfo.REASON_BOTH_NULL);
        }
        TransformInfo info = (faqQuery == null || planQuery == null)
                ? TransformInfo.partialNull(faqQuery == null, planQuery == null)
                : TransformInfo.changed();
        return new TransformOutcome(
                new QueryTransformResult(faqQuery, planQuery, intent, structured, priceRange), info);
    }
    
    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static TransformOutcome fallback(String question, String reason) {
        return new TransformOutcome(QueryTransformResult.original(question), TransformInfo.fallback(reason));
    }

    /** 극값 정보 파싱. 잘못된 sort_key여도 쿼리 변환 결과는 살리고 "극값 아님"으로만 처리한다. */
    private static PlanIntent parseIntent(JsonNode node) {
        if (!node.path("is_extreme").asBoolean(false)) {
            return PlanIntent.none();
        }
        try {
            PlanSortKey sortKey = PlanSortKey.valueOf(node.path("sort_key").asText());
            int limit = Math.min(Math.max(node.path("limit").asInt(1), 1), MAX_LIMIT);
            return new PlanIntent(true, sortKey, limit);
        } catch (IllegalArgumentException e) {
            log.warn("sort_key 파싱 실패, 극값 없음으로 처리 - node={}", node);
            return PlanIntent.none();
        }
    }
    
    private static Long longOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).canConvertToLong() ? node.get(field).asLong() : null;
    }

    /** 가격 범위 파싱. min이 max보다 크면 잘못된 값이라 범위 없음으로 처리한다. */
    private static PriceRange parsePriceRange(JsonNode node) {
        Long min = longOrNull(node, "min_price_won");
        Long max = longOrNull(node, "max_price_won");
        if (min != null && max != null && min > max) {
            log.warn("가격 범위 파싱 실패, 범위 없음으로 처리 - node={}", node);
            return PriceRange.none();
        }
        return new PriceRange(min, max);
    }
    

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String loadPrompt(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 파일을 읽을 수 없습니다: " + path, e);
        }
    }
}
