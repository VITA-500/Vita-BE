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

/** 사용자 질문을 FAQ 검색용 / 요금제 검색용 쿼리로 변환한다. 실패하면 원문으로 폴백한다. */
@Slf4j
@Component
public class QueryTransformer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

            QueryTransformResult parsed = MAPPER.readValue(raw.substring(start, end + 1), QueryTransformResult.class);
            String faqQuery = blankToNull(parsed.faqQuery());
            String planQuery = blankToNull(parsed.planQuery());

            // 둘 다 null이면 "무관"이 아니라 변환 실패일 수 있으니 원문으로 검색
            if (faqQuery == null && planQuery == null) {
                return QueryTransformResult.original(question);
            }
            return new QueryTransformResult(faqQuery, planQuery);
        } catch (Exception e) {
            log.warn("질문 변환 실패, 원문 사용 - question={}", question, e);
            return QueryTransformResult.original(question);
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