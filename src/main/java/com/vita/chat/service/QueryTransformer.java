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
    
    public QueryTransformResult transform(String question, String conversationHistory) {
    	
    	if (!enabled) {
            return QueryTransformResult.original(question);
        }
    	
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

            String raw = bedrockChatClient.complete(systemPrompt, userPrompt);

            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end < start) {
                log.warn("질문 변환 응답에 JSON 없음, 원문 사용 - raw={}", raw);
                return QueryTransformResult.original(question);
            }

            JsonNode node = MAPPER.readTree(raw.substring(start, end + 1));
            String faqQuery = blankToNull(textOrNull(node, "faq_query"));
            String planQuery = blankToNull(textOrNull(node, "plan_query"));
            PlanIntent intent = parseIntent(node);
            boolean structured = node.path("is_structured").asBoolean(false);

            // 둘 다 null이면 "무관"이 아니라 변환 실패일 수 있으니 원문으로 검색 (단, 극값 질문이면 그대로 진행)
            if (faqQuery == null && planQuery == null && !intent.extreme()) {
                return QueryTransformResult.original(question);
            }
            return new QueryTransformResult(faqQuery, planQuery, intent, structured);
        } catch (Exception e) {
            log.warn("질문 변환 실패, 원문 사용 - question={}", question, e);
            return QueryTransformResult.original(question);
        }
    }
    
    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
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