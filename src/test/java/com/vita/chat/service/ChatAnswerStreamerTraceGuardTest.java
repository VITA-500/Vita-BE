package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vita.chat.client.LlmStreamClient;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.chat.service.ChatContextBuilder.ChatContext;
import com.vita.chat.service.ChatStoreFinder.StoreLookup;
import com.vita.chat.service.QueryTransformer.TransformOutcome;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.pipeline.FaqSelection;
import com.vita.search.pipeline.RetrievalResult;
import com.vita.search.pipeline.StageTimings;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.pipeline.TransformedQuery;
import com.vita.search.service.FaqRetrievalService;
import com.vita.search.service.PlanSearchService.PlanSearchOutcome;
import com.vita.chat.dto.PlanIntent;
import com.vita.trace.SpanRecord;
import com.vita.trace.SpanType;
import com.vita.trace.TraceRecord;
import com.vita.trace.TraceRecorder;
import com.vita.trace.TraceRepository;

/**
 * 기록이 답변을 막지 않는지(기록을 만들다 실패해도, 기록을 꺼도 답변은 그대로 끝난다)와, 검색 두 단계의 시간 구간이 서로 겹치지
 * 않게 기록되는지 확인한다.
 */
class ChatAnswerStreamerTraceGuardTest {

    private static final Executor DIRECT = Runnable::run;
    private static final String QUESTION = "로밍 요금 알려줘";

    private final FaqRetrievalService faqRetrievalService = mock(FaqRetrievalService.class);
    private final QueryTransformer queryTransformer = mock(QueryTransformer.class);
    private final ChatContextBuilder chatContextBuilder = mock(ChatContextBuilder.class);
    private final ChatStoreFinder chatStoreFinder = mock(ChatStoreFinder.class);
    private final ChatMessagePersistence persistence = mock(ChatMessagePersistence.class);
    private final TraceRepository traceRepository = mock(TraceRepository.class);
    private final TraceConfigProvider configProvider = mock(TraceConfigProvider.class);

    private ChatAnswerStreamer streamer(boolean traceEnabled) {
        TraceRecorder recorder = new TraceRecorder(traceRepository, DIRECT, traceEnabled, "test");
        when(queryTransformer.transformWithStatus(anyString(), any())).thenReturn(new TransformOutcome(
                new QueryTransformResult("FAQ용", "요금제용", PlanIntent.none(), false), TransformInfo.changed()));
        when(configProvider.snapshot(anyInt())).thenReturn(Map.of("faq_threshold", 0.83));
        when(chatContextBuilder.build(anyString(), anyList(), anyList(), any(), any(), any(), any()))
                .thenReturn(ChatContext.empty());
        when(chatStoreFinder.find(any(), any(), any())).thenReturn(StoreLookup.empty());
        return new ChatAnswerStreamer(mock(ChatSseRegistry.class), faqRetrievalService, queryTransformer, chatContextBuilder,
                chatStoreFinder, mock(LlmStreamClient.class), persistence, recorder, configProvider, DIRECT);
    }

    private List<SpanRecord> savedSpans() {
        ArgumentCaptor<TraceRecord> captor = ArgumentCaptor.forClass(TraceRecord.class);
        verify(traceRepository).save(captor.capture());
        return captor.getValue().spans();
    }

    @Test
    void giveseachSearchSpanItsOwnTimeWindowInsteadOfTheWholeCall() {
        // 검색 한 번이 약 150ms 걸렸고 안에서 임베딩 40 + FAQ 검색 10 + 요금제 검색 20ms를 썼다고 가정한다.
        StageTimings timings = new StageTimings(0, 40_000_000L, 10_000_000L, 20_000_000L, 1_000_000L);
        when(faqRetrievalService.searchWithDetails(anyString(), any(), any(), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(150);
            return TestRetrievalResults.searchedBoth(QUESTION, "FAQ용", "요금제용", timings);
        });
        ChatAnswerStreamer streamer = streamer(true);

        streamer.startAsync(1L, 99L, QUESTION, null, null, null);

        List<SpanRecord> spans = savedSpans();
        SpanRecord faq = spans.stream().filter(s -> s.type() == SpanType.FAQ_RETRIEVE).findFirst().orElseThrow();
        SpanRecord plan = spans.stream().filter(s -> s.type() == SpanType.PLAN_RETRIEVE).findFirst().orElseThrow();
        long faqMs = java.time.Duration.between(faq.startedAt(), faq.endedAt()).toMillis();
        long planMs = java.time.Duration.between(plan.startedAt(), plan.endedAt()).toMillis();
        // FAQ 쪽은 임베딩 + FAQ 검색(약 50ms), 요금제 쪽은 요금제 검색(약 20ms). 두 구간은 이어지고 겹치지 않는다.
        assertThat(faqMs).isBetween(45L, 55L);
        assertThat(planMs).isBetween(15L, 25L);
        assertThat(plan.startedAt()).isEqualTo(faq.endedAt());
    }

    @Test
    void keepsAnsweringWhenBuildingASpanRecordFails() {
        // 검색 결과의 기록용 정보가 깨져 있어 FAQ·요금제 단계 기록을 만들다 예외가 나도, 답변은 정상으로 끝나야 한다.
        RetrievalResult broken = TestRetrievalResults.withBrokenTraceInfo(QUESTION, "FAQ용", "요금제용");
        when(faqRetrievalService.searchWithDetails(anyString(), any(), any(), anyInt())).thenReturn(broken);
        ChatAnswerStreamer streamer = streamer(true);

        streamer.startAsync(1L, 99L, QUESTION, null, null, null);

        verify(persistence).markCompleted(any(), anyString(), anyList(), any(), anyList(), anyList(), any(), any());
        verify(persistence, never()).markFailed(any(), anyString());
        List<SpanRecord> spans = savedSpans();
        SpanRecord faq = spans.stream().filter(s -> s.type() == SpanType.FAQ_RETRIEVE).findFirst().orElseThrow();
        assertThat(faq.history()).containsEntry("history_error", "NullPointerException");
    }

    @Test
    void keepsAnsweringWhenTheConfigSnapshotFails() {
        when(faqRetrievalService.searchWithDetails(anyString(), any(), any(), anyInt()))
                .thenReturn(TestRetrievalResults.searchedBoth(QUESTION, "FAQ용", "요금제용"));
        ChatAnswerStreamer streamer = streamer(true);
        when(configProvider.snapshot(anyInt())).thenThrow(new IllegalStateException("설정 오류"));

        streamer.startAsync(1L, 99L, QUESTION, null, null, null);

        verify(persistence).markCompleted(any(), anyString(), anyList(), any(), anyList(), anyList(), any(), any());
        verify(persistence, never()).markFailed(any(), anyString());
    }

    @Test
    void buildsNothingAndSavesNothingWhenTracingIsDisabled() {
        when(faqRetrievalService.searchWithDetails(anyString(), any(), any(), anyInt()))
                .thenReturn(TestRetrievalResults.searchedBoth(QUESTION, "FAQ용", "요금제용"));
        ChatAnswerStreamer streamer = streamer(false);

        streamer.startAsync(1L, 99L, QUESTION, null, null, null);

        verify(configProvider, never()).snapshot(anyInt());
        verify(traceRepository, never()).save(any());
        verify(persistence).markCompleted(any(), anyString(), anyList(), any(), anyList(), anyList(), any(), any());
    }
}
