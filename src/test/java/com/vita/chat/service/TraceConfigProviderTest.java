package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vita.chat.client.BedrockChatClient;
import com.vita.search.pipeline.RetrievalSettings;

/** 설정 스냅샷이 임계값·모델·프롬프트 해시를 담고, 프롬프트가 바뀌면 해시가 달라지는지 확인한다. */
class TraceConfigProviderTest {

    private static final RetrievalSettings SETTINGS = new RetrievalSettings(0.83, 0.79, true, 0.01, 5, 20);

    private static LlmQueryTransformer transformerWith(String prompt) {
        LlmQueryTransformer transformer = mock(LlmQueryTransformer.class);
        when(transformer.systemPrompt()).thenReturn(prompt);
        return transformer;
    }

    @Test
    void holdsThresholdsPoolSizeModelAndPromptHashes() {
        TraceConfigProvider provider = new TraceConfigProvider(SETTINGS, transformerWith("변환 프롬프트"), true,
                "global.anthropic.claude-sonnet-4-6", 0.3);

        Map<String, Object> snapshot = provider.snapshot(10);

        assertThat(snapshot).containsEntry("faq_threshold", 0.83).containsEntry("plan_threshold", 0.79)
                .containsEntry("faq_pool_size", 30).containsEntry("faq_top_k", 10).containsEntry("plan_top_k", 5)
                .containsEntry("plan_matched_limit", 20).containsEntry("category_boost_bonus", 0.01)
                .containsEntry("irrelevant_rule_enabled", true).containsEntry("query_transform_enabled", true)
                .containsEntry("embedding_model", "intfloat/multilingual-e5-base")
                .containsEntry("llm_model_id", "global.anthropic.claude-sonnet-4-6")
                .containsEntry("llm_temperature", 0.3);
        assertThat((String) snapshot.get("query_transform_prompt_sha256")).hasSize(64).matches("[0-9a-f]+");
        assertThat(snapshot.get("answer_system_prompt_sha256"))
                .isEqualTo(TraceConfigProvider.sha256(BedrockChatClient.answerSystemPrompt()));
    }

    @Test
    void changesTheHashWhenThePromptChangesAndKeepsItWhenItDoesNot() {
        Object first = new TraceConfigProvider(SETTINGS, transformerWith("A"), true, "m", 0.3)
                .snapshot(10).get("query_transform_prompt_sha256");
        Object same = new TraceConfigProvider(SETTINGS, transformerWith("A"), true, "m", 0.3)
                .snapshot(10).get("query_transform_prompt_sha256");
        Object changed = new TraceConfigProvider(SETTINGS, transformerWith("B"), true, "m", 0.3)
                .snapshot(10).get("query_transform_prompt_sha256");

        assertThat(same).isEqualTo(first);
        assertThat(changed).isNotEqualTo(first);
    }

    @Test
    void usesTheRequestedTopKForPoolSizeAndLeavesUnknownModelSettingsEmpty() {
        TraceConfigProvider provider = new TraceConfigProvider(SETTINGS, transformerWith("A"), false, " ", null);

        Map<String, Object> snapshot = provider.snapshot(50);

        assertThat(snapshot).containsEntry("faq_top_k", 50).containsEntry("query_transform_enabled", false);
        assertThat((Integer) snapshot.get("faq_pool_size")).isGreaterThanOrEqualTo(50);
        assertThat(snapshot.get("llm_model_id")).isNull();
        assertThat(snapshot.get("llm_temperature")).isNull();
        assertThat(TraceConfigProvider.sha256(null)).isNull();
    }
}
