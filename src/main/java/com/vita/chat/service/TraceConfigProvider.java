package com.vita.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.vita.chat.client.BedrockChatClient;
import com.vita.embedding.EmbeddingConstants;
import com.vita.search.pipeline.RetrievalSettings;
import com.vita.search.service.FaqCandidateSelector;

/**
 * 질문 처리 기록(trace)에 함께 남길 설정 스냅샷을 만든다. 임계값, 모델, 프롬프트가 바뀌는 시기에 "그때의 값"을 알아야 시기별 결과를
 * 비교할 수 있기 때문이다. 프롬프트는 길어서 원문 대신 SHA-256 해시로 구분한다(내용이 바뀌면 해시가 달라진다).
 *
 * <p>값은 앱이 뜰 때 정해지고 질문마다 바뀌지 않아서, 해시는 한 번만 계산해 두고 질문마다 맵만 새로 조립한다.
 */
@Component
public class TraceConfigProvider {

    private final RetrievalSettings settings;
    private final boolean queryTransformEnabled;
    private final String llmModelId;
    private final Double llmTemperature;
    private final String queryTransformPromptSha256;
    private final String answerSystemPromptSha256;

    public TraceConfigProvider(RetrievalSettings settings, LlmQueryTransformer queryTransformer,
            @Value("${vita.experiment.query-transform:true}") boolean queryTransformEnabled,
            @Value("${spring.ai.bedrock.converse.chat.options.model:}") String llmModelId,
            @Value("${spring.ai.bedrock.converse.chat.options.temperature:#{null}}") Double llmTemperature) {
        this.settings = settings;
        this.queryTransformEnabled = queryTransformEnabled;
        this.llmModelId = llmModelId == null || llmModelId.isBlank() ? null : llmModelId;
        this.llmTemperature = llmTemperature;
        this.queryTransformPromptSha256 = sha256(queryTransformer.systemPrompt());
        this.answerSystemPromptSha256 = sha256(BedrockChatClient.answerSystemPrompt());
    }

    /**
     * @param faqTopK 이번 질문에서 FAQ를 몇 개까지 가져오는지(후보 풀 크기는 이 값에서 정해진다)
     */
    public Map<String, Object> snapshot(int faqTopK) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("faq_threshold", settings.faqThreshold());
        map.put("plan_threshold", settings.planThreshold());
        map.put("faq_pool_size", FaqCandidateSelector.poolSize(faqTopK));
        map.put("faq_top_k", faqTopK);
        map.put("plan_top_k", settings.planTopK());
        map.put("plan_matched_limit", settings.planMatchedLimit());
        map.put("category_boost_bonus", settings.categoryBoostBonus());
        map.put("irrelevant_rule_enabled", settings.irrelevantRuleEnabled());
        map.put("query_transform_enabled", queryTransformEnabled);
        map.put("embedding_model", EmbeddingConstants.MODEL_NAME);
        map.put("embedding_version", EmbeddingConstants.VERSION);
        map.put("llm_model_id", llmModelId);
        map.put("llm_temperature", llmTemperature);
        map.put("query_transform_prompt_sha256", queryTransformPromptSha256);
        map.put("answer_system_prompt_sha256", answerSystemPromptSha256);
        return map;
    }

    /** 문자열의 SHA-256을 16진수로 돌려준다. 없으면 null. */
    static String sha256(String text) {
        if (text == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 모든 JVM이 SHA-256을 지원한다. 지원하지 않으면 해시만 비우고 기록은 계속한다.
            return null;
        }
    }
}
