package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.vita.search.pipeline.RetrievalSettings;

/**
 * 스프링이 설정 파일의 값을 {@code TraceConfigProvider}에 제대로 넣는지 확인한다. 모델·온도 설정이 없어도 앱이 뜨는지(기본값 처리)가
 * 생성자 단위 테스트로는 확인되지 않아서, 가벼운 컨텍스트로 값 주입만 따로 검증한다.
 */
class TraceConfigProviderWiringTest {

    @Configuration
    @Import(TraceConfigProvider.class)
    static class Deps {
        @Bean
        RetrievalSettings retrievalSettings() {
            return new RetrievalSettings(0.83, 0.79, true, 0.01, 5, 20);
        }

        @Bean
        LlmQueryTransformer queryTransformer() {
            LlmQueryTransformer transformer = mock(LlmQueryTransformer.class);
            when(transformer.systemPrompt()).thenReturn("변환 프롬프트");
            return transformer;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Deps.class);

    @Test
    void injectsTheModelTemperatureAndTransformFlagFromProperties() {
        runner.withPropertyValues(
                "spring.ai.bedrock.converse.chat.options.model=global.anthropic.claude-sonnet-4-6",
                "spring.ai.bedrock.converse.chat.options.temperature=0.3",
                "vita.experiment.query-transform=false").run(context -> {
            Map<String, Object> snapshot = context.getBean(TraceConfigProvider.class).snapshot(10);

            assertThat(snapshot).containsEntry("llm_model_id", "global.anthropic.claude-sonnet-4-6")
                    .containsEntry("llm_temperature", 0.3).containsEntry("query_transform_enabled", false);
        });
    }

    @Test
    void startsWithDefaultsWhenTheModelAndTemperatureAreNotConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            Map<String, Object> snapshot = context.getBean(TraceConfigProvider.class).snapshot(10);

            assertThat(snapshot.get("llm_model_id")).isNull();
            assertThat(snapshot.get("llm_temperature")).isNull();
            assertThat(snapshot).containsEntry("query_transform_enabled", true);
        });
    }
}
