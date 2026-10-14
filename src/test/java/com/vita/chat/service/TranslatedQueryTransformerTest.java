package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vita.chat.FallbackType;
import com.vita.chat.service.QuestionTranslator.TranslationOutcome;
import com.vita.search.pipeline.QueryTransformer;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.pipeline.TransformedQuery;

class TranslatedQueryTransformerTest {

    private static final String QUESTION = "해외에서 데이터 쓰면 요금 얼마예요?";
    private static final String FAQ_QUERY = "조건: 해외 데이터 이용\n질문: 해외에서 데이터를 쓰면 요금이 얼마인가요?\n핵심 키워드: 해외, 데이터, 로밍";
    private static final String PLAN_QUERY = "핵심 키워드: 해외 로밍 요금제";

    /** 입력 앞에 "EN:"을 붙여 돌려주고 호출한 입력을 기록하는 번역기. */
    private static final class RecordingTranslator implements QuestionTranslator {
        final List<String> inputs = new ArrayList<>();

        @Override
        public TranslationOutcome translateWithStatus(String question) {
            inputs.add(question);
            return new TranslationOutcome(question, "EN:" + question, false, null, null);
        }
    }

    private static QueryTransformer returning(TransformedQuery transformed) {
        return query -> transformed;
    }

    @Test
    void translatesTheFaqAndPlanQueriesOfTheQueryTransformationSeparately() {
        RecordingTranslator translator = new RecordingTranslator();
        TranslatedQueryTransformer transformer = new TranslatedQueryTransformer(
                returning(new TransformedQuery(QUESTION, FAQ_QUERY, PLAN_QUERY, TransformInfo.changed())), translator);

        TransformedQuery result = transformer.transform(QUESTION);

        assertThat(result.original()).isEqualTo(QUESTION);
        assertThat(result.faqQuery()).isEqualTo("EN:" + FAQ_QUERY);
        assertThat(result.planQuery()).isEqualTo("EN:" + PLAN_QUERY);
        assertThat(result.info()).isEqualTo(TransformInfo.changed());
        assertThat(translator.inputs).containsExactly(FAQ_QUERY, PLAN_QUERY);
    }

    @Test
    void translatesOnceWhenTheFaqAndPlanQueriesAreTheSame() {
        RecordingTranslator translator = new RecordingTranslator();
        TranslatedQueryTransformer transformer = new TranslatedQueryTransformer(
                returning(new TransformedQuery(QUESTION, QUESTION, QUESTION, TransformInfo.fallback(TransformInfo.REASON_BOTH_NULL))),
                translator);

        TransformedQuery result = transformer.transform(QUESTION);

        assertThat(translator.inputs).containsExactly(QUESTION);
        assertThat(result.faqQuery()).isEqualTo("EN:" + QUESTION);
        assertThat(result.planQuery()).isEqualTo("EN:" + QUESTION);
    }

    @Test
    void keepsTheQueryTransformationRecordWhenTheTranslationSucceeds() {
        TransformInfo partial = TransformInfo.partialNull(true, false);
        TranslatedQueryTransformer transformer = new TranslatedQueryTransformer(
                returning(new TransformedQuery(QUESTION, QUESTION, PLAN_QUERY, partial)), new RecordingTranslator());

        assertThat(transformer.transform(QUESTION).info()).isEqualTo(partial);
    }

    @Test
    void keepsTheQueryTransformationFallbackRecordWhenOnlyTheQueryTransformationFailed() {
        TransformInfo bothNull = TransformInfo.fallback(TransformInfo.REASON_BOTH_NULL);
        TranslatedQueryTransformer transformer = new TranslatedQueryTransformer(
                returning(new TransformedQuery(QUESTION, QUESTION, QUESTION, bothNull)), new RecordingTranslator());

        assertThat(transformer.transform(QUESTION).info()).isEqualTo(bothNull);
    }

    @Test
    void doesNotTranslateAQueryTheTransformerLeftEmpty() {
        RecordingTranslator translator = new RecordingTranslator();
        TranslatedQueryTransformer transformer = new TranslatedQueryTransformer(
                returning(new TransformedQuery(QUESTION, null, PLAN_QUERY, TransformInfo.partialNull(true, false))), translator);

        TransformedQuery result = transformer.transform(QUESTION);

        assertThat(result.faqQuery()).isNull();
        assertThat(result.planQuery()).isEqualTo("EN:" + PLAN_QUERY);
        assertThat(translator.inputs).containsExactly(PLAN_QUERY);
    }

    @Test
    void recordsTheTranslationReasonAndSearchesWithTheKoreanQueryWhenTheTranslationFallsBack() {
        QuestionTranslator failing = question ->
                new TranslationOutcome(question, question, true, FallbackType.VALIDATION, "number lost or changed: 5");
        TranslatedQueryTransformer transformer = new TranslatedQueryTransformer(
                returning(new TransformedQuery(QUESTION, FAQ_QUERY, PLAN_QUERY, TransformInfo.changed())), failing);

        TransformedQuery result = transformer.transform(QUESTION);

        assertThat(result.faqQuery()).isEqualTo(FAQ_QUERY);
        assertThat(result.planQuery()).isEqualTo(PLAN_QUERY);
        assertThat(result.info().kind()).isEqualTo(TransformInfo.Kind.FALLBACK_ORIGINAL);
        assertThat(result.info().reason()).isEqualTo("TRANSLATION_VALIDATION");
    }

    @Test
    void recordsATranslationFallbackEvenWhenOnlyOneSideFailed() {
        QuestionTranslator failsOnPlanOnly = question -> question.equals(PLAN_QUERY)
                ? new TranslationOutcome(question, question, true, FallbackType.INPUT, "blank input")
                : new TranslationOutcome(question, "EN:" + question, false, null, null);
        TranslatedQueryTransformer transformer = new TranslatedQueryTransformer(
                returning(new TransformedQuery(QUESTION, FAQ_QUERY, PLAN_QUERY, TransformInfo.changed())), failsOnPlanOnly);

        TransformedQuery result = transformer.transform(QUESTION);

        assertThat(result.faqQuery()).isEqualTo("EN:" + FAQ_QUERY);
        assertThat(result.planQuery()).isEqualTo(PLAN_QUERY);
        assertThat(result.info().reason()).isEqualTo("TRANSLATION_INPUT");
    }

    @Test
    void putsTheTranslationFallbackAheadOfTheQueryTransformationFallback() {
        QuestionTranslator failing = question ->
                new TranslationOutcome(question, question, true, FallbackType.VALIDATION, "plan name not preserved");
        TranslatedQueryTransformer transformer = new TranslatedQueryTransformer(
                returning(new TransformedQuery(QUESTION, QUESTION, QUESTION, TransformInfo.fallback(TransformInfo.REASON_BOTH_NULL))),
                failing);

        assertThat(transformer.transform(QUESTION).info().reason()).isEqualTo("TRANSLATION_VALIDATION");
    }

    @Test
    void reportsLlmErrorsAsCallFailedSoTheEvalCacheRetriesThem() {
        QuestionTranslator failing = question ->
                new TranslationOutcome(question, question, true, FallbackType.LLM_ERROR, "llm error: SdkClientException");
        TranslatedQueryTransformer transformer = new TranslatedQueryTransformer(
                returning(new TransformedQuery(QUESTION, FAQ_QUERY, PLAN_QUERY, TransformInfo.changed())), failing);

        assertThat(transformer.transform(QUESTION).info())
                .isEqualTo(TransformInfo.fallback(TransformInfo.REASON_CALL_FAILED));
    }

    @Test
    void hasTheBeanNameTheExperimentSettingsReferTo() {
        assertThat(TranslatedQueryTransformer.BEAN_NAME).isEqualTo("translatedQueryTransformer");
    }

    // ---- 질문 변환 단계에서 C의 저장된 결과 다시 쓰기 ----

    private static void writeStored(Path file, String original, String faqQuery, String planQuery) throws IOException {
        String line = "{\"original\":\"" + original + "\",\"faqQuery\":\"" + faqQuery + "\",\"planQuery\":\"" + planQuery
                + "\",\"kind\":\"PARTIAL_NULL\",\"reason\":\"\",\"faqNull\":true,\"planNull\":false}\n";
        Files.writeString(file, line, StandardCharsets.UTF_8);
    }

    @Test
    void usesTheStoredQueryTransformationAndNeverCallsTheLiveTransformer(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.jsonl");
        writeStored(file, "저장된 질문", "저장된 FAQ용 질문", "저장된 요금제용 질문");
        List<String> liveCalls = new ArrayList<>();
        QueryTransformer live = query -> {
            liveCalls.add(query);
            return TransformedQuery.unchanged(query);
        };
        RecordingTranslator translator = new RecordingTranslator();
        TranslatedQueryTransformer transformer =
                new TranslatedQueryTransformer(TranslatedQueryTransformer.withStoredResults(live, file.toString()), translator);

        TransformedQuery result = transformer.transform("저장된 질문");

        assertThat(liveCalls).isEmpty();
        assertThat(result.faqQuery()).isEqualTo("EN:저장된 FAQ용 질문");
        assertThat(result.planQuery()).isEqualTo("EN:저장된 요금제용 질문");
        assertThat(result.info().kind()).isEqualTo(TransformInfo.Kind.PARTIAL_NULL);
    }

    @Test
    void failsInsteadOfCallingTheLiveTransformerForAQuestionThatIsNotStored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.jsonl");
        writeStored(file, "저장된 질문", "FAQ", "요금제");
        List<String> liveCalls = new ArrayList<>();
        QueryTransformer chain = TranslatedQueryTransformer.withStoredResults(query -> {
            liveCalls.add(query);
            return TransformedQuery.unchanged(query);
        }, file.toString());

        assertThatThrownBy(() -> chain.transform("없는 질문"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("없는 질문");
        assertThat(liveCalls).isEmpty();
    }

    @Test
    void searchesSeveralStoredFilesInOrder(@TempDir Path dir) throws IOException {
        Path first = dir.resolve("faq.jsonl");
        Path second = dir.resolve("plan.jsonl");
        writeStored(first, "FAQ 질문", "FAQ용", "FAQ요금제용");
        writeStored(second, "요금제 질문", "요금제FAQ용", "요금제용");
        QueryTransformer chain = TranslatedQueryTransformer.withStoredResults(
                TransformedQuery::unchanged, first + "," + second);

        assertThat(chain.transform("FAQ 질문").faqQuery()).isEqualTo("FAQ용");
        assertThat(chain.transform("요금제 질문").planQuery()).isEqualTo("요금제용");
    }

    @Test
    void refusesAStoredResultFileThatDoesNotExist(@TempDir Path dir) {
        QueryTransformer live = TransformedQuery::unchanged;
        String missing = dir.resolve("none.jsonl").toString();

        assertThatThrownBy(() -> TranslatedQueryTransformer.withStoredResults(live, missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("none.jsonl");
    }

    @Test
    void callsTheLiveTransformerWhenNoStoredFileIsGiven() {
        QueryTransformer live = TransformedQuery::unchanged;

        assertThat(TranslatedQueryTransformer.withStoredResults(live, "")).isSameAs(live);
        assertThat(TranslatedQueryTransformer.withStoredResults(live, null)).isSameAs(live);
    }
}
