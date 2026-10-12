package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.vita.chat.FallbackType;
import com.vita.chat.service.QuestionTranslator.TranslationOutcome;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.pipeline.TransformedQuery;

class QuestionTranslatorAdapterTest {

    private static final String QUESTION = "데이터 무제한 요금제 알려줘";

    @Test
    void usesTheSameTranslationForFaqAndPlanSearchAndKeepsTheOriginal() {
        QuestionTranslatorAdapter adapter = adapterReturning(
                new TranslationOutcome(QUESTION, "Tell me about unlimited data plans", false, null, null));

        TransformedQuery transformed = adapter.transform(QUESTION);

        assertThat(transformed.original()).isEqualTo(QUESTION);
        assertThat(transformed.faqQuery()).isEqualTo("Tell me about unlimited data plans");
        assertThat(transformed.planQuery()).isEqualTo("Tell me about unlimited data plans");
        assertThat(transformed.info()).isEqualTo(TransformInfo.changed());
    }

    @Test
    void searchesWithTheOriginalAndRecordsTheReasonWhenTranslationFallsBack() {
        QuestionTranslatorAdapter adapter = adapterReturning(
                new TranslationOutcome(QUESTION, QUESTION, true, FallbackType.VALIDATION, "number lost or changed: 5"));

        TransformedQuery transformed = adapter.transform(QUESTION);

        assertThat(transformed.faqQuery()).isEqualTo(QUESTION);
        assertThat(transformed.planQuery()).isEqualTo(QUESTION);
        assertThat(transformed.info().kind()).isEqualTo(TransformInfo.Kind.FALLBACK_ORIGINAL);
        assertThat(transformed.info().reason()).isEqualTo("TRANSLATION_VALIDATION");
    }

    @Test
    void reportsLlmErrorsAsCallFailedSoTheEvalCacheRetriesThem() {
        QuestionTranslatorAdapter adapter = adapterReturning(
                new TranslationOutcome(QUESTION, QUESTION, true, FallbackType.LLM_ERROR, "llm error: SdkClientException"));

        assertThat(adapter.transform(QUESTION).info())
                .isEqualTo(TransformInfo.fallback(TransformInfo.REASON_CALL_FAILED));
    }

    @Test
    void hasTheBeanNameTheExperimentSettingsReferTo() {
        assertThat(QuestionTranslatorAdapter.BEAN_NAME).isEqualTo("translatingQueryTransformer");
    }

    private static QuestionTranslatorAdapter adapterReturning(TranslationOutcome outcome) {
        return new QuestionTranslatorAdapter(question -> outcome);
    }
}
