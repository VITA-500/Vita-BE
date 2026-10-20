package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.chat.client.LlmStreamClient;
import com.vita.chat.dto.DataRange;
import com.vita.chat.dto.PlanIntent;
import com.vita.chat.dto.PriceRange;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.chat.dto.StoreIntent;
import com.vita.chat.service.ChatContextBuilder.ChatContext;
import com.vita.chat.service.ChatContextBuilder.ExtremeLookup;
import com.vita.chat.service.ChatStoreFinder.StoreLookup;
import com.vita.chat.service.QueryTransformer.TransformOutcome;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.service.FaqRetrievalService;
import com.vita.search.service.PlanSortKey;
import com.vita.trace.SpanRecord;
import com.vita.trace.SpanType;
import com.vita.trace.TraceRecord;
import com.vita.trace.TraceRecorder;
import com.vita.trace.TraceRepository;

/** 질문 종류(일반, 조건만, 극값, 매장)에 따라 검색 단계 span이 사유와 함께 기록되는지 확인한다. */
class ChatAnswerStreamerRetrievalTraceTest {

    private static final Executor DIRECT = Runnable::run;
    private static final String QUESTION = "로밍 요금 알려줘";

    private final FaqRetrievalService faqRetrievalService = mock(FaqRetrievalService.class);
    private final QueryTransformer queryTransformer = mock(QueryTransformer.class);
    private final ChatContextBuilder chatContextBuilder = mock(ChatContextBuilder.class);
    private final ChatStoreFinder chatStoreFinder = mock(ChatStoreFinder.class);
    private final ChatMessagePersistence persistence = mock(ChatMessagePersistence.class);
    private final TraceRepository traceRepository = mock(TraceRepository.class);

    private ChatAnswerStreamer streamerFor(QueryTransformResult transformed) {
        TraceRecorder recorder = new TraceRecorder(traceRepository, DIRECT, true, "test");
        when(queryTransformer.transformWithStatus(anyString(), any()))
                .thenReturn(new TransformOutcome(transformed, TransformInfo.changed()));
        when(faqRetrievalService.searchWithDetails(anyString(), any(), any(), anyInt())).thenAnswer(invocation ->
                TestRetrievalResults.searchedBoth(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(chatContextBuilder.build(anyString(), anyList(), anyList(), any(), any(), any(), any()))
                .thenReturn(ChatContext.empty());
        when(chatStoreFinder.find(any(), any(), any(), any())).thenReturn(StoreLookup.empty());
        return new ChatAnswerStreamer(mock(ChatSseRegistry.class), faqRetrievalService, queryTransformer, chatContextBuilder,
                chatStoreFinder, mock(LlmStreamClient.class), persistence, recorder, configProvider(), DIRECT);
    }

    private static TraceConfigProvider configProvider() {
        TraceConfigProvider provider = mock(TraceConfigProvider.class);
        when(provider.snapshot(anyInt())).thenReturn(Map.of("faq_threshold", 0.83, "llm_model_id", "model-x"));
        return provider;
    }

    private List<SpanRecord> savedSpans() {
        ArgumentCaptor<TraceRecord> captor = ArgumentCaptor.forClass(TraceRecord.class);
        verify(traceRepository).save(captor.capture());
        return captor.getValue().spans();
    }

    private static List<SpanType> types(List<SpanRecord> spans) {
        return spans.stream().map(SpanRecord::type).toList();
    }

    @Test
    void recordsBothRetrievalSpansWithTheirDetailsForANormalQuestion() {
        ChatAnswerStreamer streamer = streamerFor(new QueryTransformResult("FAQ용 질의", "요금제용 질의", PlanIntent.none(), false));

        streamer.startAsync(1L, 99L, QUESTION, null, null, null);

        List<SpanRecord> spans = savedSpans();
        assertThat(types(spans)).containsExactly(SpanType.TRANSFORM, SpanType.FAQ_RETRIEVE, SpanType.PLAN_RETRIEVE,
                SpanType.GENERATION);
        assertThat(spans.get(1).history()).containsEntry("type", "FAQ").containsEntry("query", "FAQ용 질의")
                .doesNotContainKeys("skipped", "fallback_of");
        assertThat(spans.get(2).history()).containsEntry("type", "PLAN").containsEntry("query", "요금제용 질의");
    }

    @Test
    void marksTheFaqSpanAsPlanOnlyWhenTheStreamerSkipsFaqSearchForAConditionQuestion() {
        ChatAnswerStreamer streamer = streamerFor(new QueryTransformResult(null, "3만원대 요금제", PlanIntent.none(), true));

        streamer.startAsync(1L, 99L, QUESTION, null, null, null);

        List<SpanRecord> spans = savedSpans();
        assertThat(types(spans)).containsExactly(SpanType.TRANSFORM, SpanType.FAQ_RETRIEVE, SpanType.PLAN_RETRIEVE,
                SpanType.GENERATION);
        assertThat(spans.get(1).history()).containsOnly(Map.entry("skipped", "PLAN_ONLY"));
        assertThat(spans.get(2).history()).containsEntry("type", "PLAN");
    }

    @Test
    void recordsDbOnlySkipThenTheFallbackSearchWhenTheExtremeLookupReturnsNothing() {
        QueryTransformResult extreme = new QueryTransformResult("FAQ용", "요금제용",
                new PlanIntent(true, PlanSortKey.CHEAPEST, 1), true);
        ChatAnswerStreamer streamer = streamerFor(extreme);
        ExtremeLookup noResult = new ExtremeLookup(PlanSortKey.CHEAPEST, 1, "GENERAL", null, PriceRange.none(),
                DataRange.none(), false, 0, 0, List.of(), false);
        when(chatContextBuilder.build(anyString(), anyList(), anyList(), any(), any(), any(), any()))
                .thenReturn(new ChatContext("", List.of(), List.of(), noResult));

        streamer.startAsync(1L, 99L, QUESTION, null, null, null);

        List<SpanRecord> spans = savedSpans();
        assertThat(types(spans)).containsExactly(SpanType.TRANSFORM, SpanType.FAQ_RETRIEVE, SpanType.PLAN_RETRIEVE,
                SpanType.PLAN_LOOKUP, SpanType.FAQ_RETRIEVE, SpanType.PLAN_RETRIEVE, SpanType.PLAN_LOOKUP, SpanType.GENERATION);
        assertThat(spans.get(1).history()).containsOnly(Map.entry("skipped", "DB_ONLY"));
        assertThat(spans.get(2).history()).containsOnly(Map.entry("skipped", "DB_ONLY"));
        // 첫 조회는 비어서 일반 검색으로 넘어갔다고 표시한다
        assertThat(spans.get(3).history()).containsEntry("sort_key", "CHEAPEST").containsEntry("fallback_to_search", true);
        // 폴백으로 실제 검색한 span에는 처음 시도한 경로가 남는다
        assertThat(spans.get(4).history()).containsEntry("fallback_of", "dbOnly").containsEntry("type", "FAQ");
        assertThat(spans.get(5).history()).containsEntry("fallback_of", "dbOnly").containsEntry("type", "PLAN");
        assertThat(spans.get(6).history()).containsEntry("fallback_of", "dbOnly").containsEntry("fallback_to_search", false);
    }

    @Test
    void recordsThePlanLookupBetweenSearchAndGenerationForAnExtremeQuestionThatFound() {
        QueryTransformResult extreme = new QueryTransformResult("FAQ용", "요금제용",
                new PlanIntent(true, PlanSortKey.MOST_DATA, 1), false);
        ChatAnswerStreamer streamer = streamerFor(extreme);
        ExtremeLookup found = new ExtremeLookup(PlanSortKey.MOST_DATA, 1, "GENERAL", null, PriceRange.none(),
                DataRange.none(), false, 2, 2, List.of(), false);
        when(chatContextBuilder.build(anyString(), anyList(), anyList(), any(), any(), any(), any()))
                .thenReturn(new ChatContext("<comparison_result/>", List.of(), List.of(), found));

        streamer.startAsync(1L, 99L, QUESTION, null, null, null);

        List<SpanRecord> spans = savedSpans();
        assertThat(types(spans)).containsExactly(SpanType.TRANSFORM, SpanType.FAQ_RETRIEVE, SpanType.PLAN_RETRIEVE,
                SpanType.PLAN_LOOKUP, SpanType.GENERATION);
        assertThat(spans.get(3).history()).containsEntry("fetched", 2).containsEntry("fallback_to_search", false);
    }

    @Test
    void recordsStoreOnlySkipWithoutCallingTheSearch() {
        StoreIntent store = new StoreIntent(StoreIntent.Type.PHONE, null, null, List.of(), false, null, "방학역", null);
        QueryTransformResult storeQuestion = new QueryTransformResult(null, null, PlanIntent.none(), false,
                PriceRange.none(), DataRange.none(), store);
        ChatAnswerStreamer streamer = streamerFor(storeQuestion);

        streamer.startAsync(1L, 99L, "방학역 근처 매장", null, null, null);

        List<SpanRecord> spans = savedSpans();
        assertThat(types(spans)).containsExactly(SpanType.TRANSFORM, SpanType.FAQ_RETRIEVE, SpanType.PLAN_RETRIEVE,
                SpanType.STORE_LOOKUP, SpanType.GENERATION);
        assertThat(spans.get(1).history()).containsOnly(Map.entry("skipped", "STORE_ONLY"));
        assertThat(spans.get(2).history()).containsOnly(Map.entry("skipped", "STORE_ONLY"));
        assertThat(spans.get(3).history()).containsEntry("location_provided", false).containsEntry("found", 0);
        verify(faqRetrievalService, never()).searchWithDetails(anyString(), any(), any(), anyInt());
    }

    @Test
    void storeLookupSpanOnlySaysWhetherALocationWasGivenNeverTheCoordinates() throws Exception {
        StoreIntent store = new StoreIntent(StoreIntent.Type.PHONE, null, null, List.of(), false, null, null, null);
        ChatAnswerStreamer streamer = streamerFor(new QueryTransformResult(null, null, PlanIntent.none(), false,
                PriceRange.none(), DataRange.none(), store));

        streamer.startAsync(1L, 99L, "가까운 매장", new BigDecimal("37.123456"), new BigDecimal("127.654321"), null);

        SpanRecord storeSpan = savedSpans().stream().filter(s -> s.type() == SpanType.STORE_LOOKUP).findFirst().orElseThrow();
        assertThat(storeSpan.history()).containsEntry("location_provided", true);
        assertThat(new ObjectMapper().writeValueAsString(storeSpan.history())).doesNotContain("37.123456")
                .doesNotContain("127.654321");
    }

    @Test
    void doesNotRecordAStoreSpanForAQuestionWithoutStoreIntent() {
        ChatAnswerStreamer streamer = streamerFor(new QueryTransformResult("FAQ용", "요금제용", PlanIntent.none(), false));

        streamer.startAsync(1L, 99L, QUESTION, null, null, null);

        assertThat(types(savedSpans())).doesNotContain(SpanType.STORE_LOOKUP, SpanType.PLAN_LOOKUP);
    }

    @Test
    void recordsTheErrorOnBothSpansWhenTheSearchThrows() {
        ChatAnswerStreamer streamer = streamerFor(new QueryTransformResult("FAQ용", "요금제용", PlanIntent.none(), false));
        when(faqRetrievalService.searchWithDetails(anyString(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("임베딩 서버 응답 없음"));

        streamer.startAsync(1L, 99L, QUESTION, null, null, null);

        verify(persistence).markFailed(99L, "임베딩 서버 응답 없음");
        List<SpanRecord> spans = savedSpans();
        assertThat(types(spans)).containsExactly(SpanType.TRANSFORM, SpanType.FAQ_RETRIEVE, SpanType.PLAN_RETRIEVE);
        assertThat(spans.get(1).history()).containsEntry("query", "FAQ용").containsKey("error");
        assertThat(spans.get(2).history()).containsKey("error");
        verify(chatContextBuilder, never()).build(anyString(), anyList(), anyList(), any(), any(), any(), isNull());
    }
}
