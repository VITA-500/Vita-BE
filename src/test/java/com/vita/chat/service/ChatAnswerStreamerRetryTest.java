package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import com.vita.chat.client.LlmStreamClient;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.chat.dto.StreamResult;
import com.vita.chat.entity.ChatEvents;
import com.vita.chat.entity.ChatEvents.AssistantStatus;
import com.vita.chat.service.ChatContextBuilder.ChatContext;
import com.vita.chat.service.ChatStoreFinder.StoreLookup;
import com.vita.chat.service.QueryTransformer.TransformOutcome;
import com.vita.chat.service.QueryTransformer.TransformTrace;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.service.FaqRetrievalService;
import com.vita.trace.SpanRecord;
import com.vita.trace.SpanType;
import com.vita.trace.TraceRecord;
import com.vita.trace.TraceRecorder;
import com.vita.trace.TraceRepository;

/** 답변 생성이 실패하면 한 번 더 시도한다. 답변 조각을 이미 보냈거나 시간 초과면 다시 하지 않는다. */
class ChatAnswerStreamerRetryTest {

    private static final Executor DIRECT = Runnable::run;
    private static final StreamResult RESULT = new StreamResult("프롬프트", "model-x", 1L, 1L, 2L, "시스템");

    private final ChatSseRegistry registry = mock(ChatSseRegistry.class);
    private final FaqRetrievalService faqRetrievalService = mock(FaqRetrievalService.class);
    private final QueryTransformer queryTransformer = mock(QueryTransformer.class);
    private final ChatContextBuilder chatContextBuilder = mock(ChatContextBuilder.class);
    private final ChatStoreFinder chatStoreFinder = mock(ChatStoreFinder.class);
    private final LlmStreamClient llmStreamClient = mock(LlmStreamClient.class);
    private final ChatMessagePersistence persistence = mock(ChatMessagePersistence.class);
    private final TraceRepository traceRepository = mock(TraceRepository.class);

    private ChatAnswerStreamer streamer() {
        TraceRecorder recorder = new TraceRecorder(traceRepository, DIRECT, true, "test");
        when(queryTransformer.transformWithStatus(anyString(), any())).thenReturn(new TransformOutcome(
                QueryTransformResult.original("로밍 요금 알려줘"), TransformInfo.changed(),
                new TransformTrace("프롬프트", "{\"faq_query\":\"로밍\"}", 10L, 5L, 15L)));
        when(faqRetrievalService.searchWithDetails(anyString(), any(), any(), anyInt()))
                .thenReturn(TestRetrievalResults.searchedBoth("로밍 요금 알려줘", "로밍 요금 알려줘", "로밍 요금 알려줘"));
        when(chatContextBuilder.build(anyString(), anyList(), anyList(), any(), any(), any(), any()))
                .thenReturn(ChatContext.empty());
        when(chatStoreFinder.find(any(), any(), any(), any())).thenReturn(StoreLookup.empty());
        TraceConfigProvider configProvider = mock(TraceConfigProvider.class);
        when(configProvider.snapshot(anyInt())).thenReturn(Map.of());
        ChatAnswerStreamer streamer = new ChatAnswerStreamer(registry, faqRetrievalService, queryTransformer,
                chatContextBuilder, chatStoreFinder, llmStreamClient, persistence, recorder, configProvider, DIRECT);
        streamer.retryDelayMillis = 0;
        return streamer;
    }

    private static Answer<StreamResult> answers(String text) {
        return invocation -> {
            Consumer<String> onDelta = invocation.getArgument(3);
            onDelta.accept(text);
            return RESULT;
        };
    }

    private void verifyCompletedWith(String answer) {
        verify(persistence).markCompleted(eq(99L), eq(answer), any(), any(), any(), any(), any(), any());
        verify(persistence, never()).markFailed(any(), any());
    }

    private void verifyFailed() {
        verify(persistence).markFailed(eq(99L), any());
        verify(persistence, never()).markCompleted(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private TraceRecord savedTrace() {
        ArgumentCaptor<TraceRecord> captor = ArgumentCaptor.forClass(TraceRecord.class);
        verify(traceRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void retriesOnceWhenTheFirstCallFailsBeforeAnyChunk() {
        ChatAnswerStreamer streamer = streamer();
        when(llmStreamClient.stream(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("ThrottlingException: Too many requests"))
                .thenAnswer(answers("다시 만든 답변"));

        streamer.startAsync(1L, 99L, "로밍 요금 알려줘", null, null, null);

        verify(llmStreamClient, times(2)).stream(anyString(), anyString(), any(), any());
        verify(persistence).markRetrying(99L);
        verify(registry).publish(eq(1L), eq(ChatEvents.ASSISTANT_STATUS),
                eq(new ChatEvents.Status(1L, 99L, AssistantStatus.RETRYING)));
        verifyCompletedWith("다시 만든 답변");
        // 시도마다 생성 기록을 남긴다: 첫 번째는 실패, 두 번째는 성공
        assertThat(savedTrace().spans()).filteredOn(s -> s.type() == SpanType.GENERATION)
                .extracting(SpanRecord::history)
                .satisfiesExactly(
                        first -> assertThat(first).containsKey("error"),
                        second -> assertThat(second).containsEntry("answer", "다시 만든 답변").doesNotContainKey("error"));
    }

    @Test
    void doesNotRetryAfterAChunkWasSent() {
        ChatAnswerStreamer streamer = streamer();
        when(llmStreamClient.stream(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(3);
            onDelta.accept("앞 조각");
            throw new RuntimeException("stream reset");
        });

        streamer.startAsync(1L, 99L, "로밍 요금 알려줘", null, null, null);

        verify(llmStreamClient, times(1)).stream(anyString(), anyString(), any(), any());
        verify(persistence, never()).markRetrying(any());
        verifyFailed();
    }

    @Test
    void failsWhenTheRetryAlsoFails() {
        ChatAnswerStreamer streamer = streamer();
        when(llmStreamClient.stream(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("ThrottlingException"));

        streamer.startAsync(1L, 99L, "로밍 요금 알려줘", null, null, null);

        verify(llmStreamClient, times(2)).stream(anyString(), anyString(), any(), any());
        verify(persistence, times(1)).markRetrying(99L);
        verifyFailed();
    }

    @Test
    void doesNotRetryATimeout() {
        ChatAnswerStreamer streamer = streamer();
        when(llmStreamClient.stream(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("blockLast", new TimeoutException("Timeout on blocking read")));

        streamer.startAsync(1L, 99L, "로밍 요금 알려줘", null, null, null);

        verify(llmStreamClient, times(1)).stream(anyString(), anyString(), any(), any());
        verify(persistence, never()).markRetrying(any());
        verifyFailed();
    }
}
