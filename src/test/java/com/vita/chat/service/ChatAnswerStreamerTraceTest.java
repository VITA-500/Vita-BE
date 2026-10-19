package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vita.chat.client.LlmStreamClient;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.chat.service.ChatContextBuilder.ChatContext;
import com.vita.chat.service.ChatStoreFinder.StoreLookup;
import com.vita.chat.service.QueryTransformer.TransformOutcome;
import com.vita.chat.service.QueryTransformer.TransformTrace;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.service.FaqRetrievalService;
import com.vita.trace.SpanType;
import com.vita.trace.TraceRecord;
import com.vita.trace.TraceRecorder;
import com.vita.trace.TraceRepository;

/** 답변 흐름이 끝나면(성공이든 실패든) 질문 변환 단계 기록이 저장되고, 기록이 답변 결과를 바꾸지 않는지 확인한다. */
class ChatAnswerStreamerTraceTest {

    private static final Executor DIRECT = Runnable::run;

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
        when(faqRetrievalService.search(anyString(), any(), any(), anyInt())).thenReturn(FaqRetrievalContext.empty());
        when(chatContextBuilder.build(anyString(), anyList(), anyList(), any(), any(), any(), any()))
                .thenReturn(ChatContext.empty());
        when(chatStoreFinder.find(any(), any(), any())).thenReturn(StoreLookup.empty());
        return new ChatAnswerStreamer(registry, faqRetrievalService, queryTransformer, chatContextBuilder,
                chatStoreFinder, llmStreamClient, persistence, recorder, DIRECT);
    }

    @Test
    void savesTheTransformSpanOnceTheAnswerIsCompleted() {
        ChatAnswerStreamer streamer = streamer();

        streamer.startAsync(1L, 99L, "로밍 요금 알려줘", null, null, null);

        ArgumentCaptor<TraceRecord> captor = ArgumentCaptor.forClass(TraceRecord.class);
        verify(traceRepository).save(captor.capture());
        TraceRecord record = captor.getValue();
        assertThat(record.messageId()).isEqualTo(99L);
        assertThat(record.spans()).hasSize(1);
        assertThat(record.spans().get(0).type()).isEqualTo(SpanType.TRANSFORM);
        assertThat(record.spans().get(0).history())
                .containsEntry("question", "로밍 요금 알려줘")
                .containsEntry("raw_response", "{\"faq_query\":\"로밍\"}")
                .containsEntry("total_tokens", 15L);
    }

    @Test
    void stillSavesTheTraceWhenLaterStepsFailAndMarksTheMessageFailed() {
        ChatAnswerStreamer streamer = streamer();
        doThrow(new IllegalStateException("LLM 오류")).when(llmStreamClient).stream(anyString(), anyString(), any(), any());

        streamer.startAsync(1L, 99L, "로밍 요금 알려줘", null, null, null);

        verify(persistence).markFailed(99L, "LLM 오류");
        ArgumentCaptor<TraceRecord> captor = ArgumentCaptor.forClass(TraceRecord.class);
        verify(traceRepository).save(captor.capture());
        assertThat(captor.getValue().spans()).extracting(s -> s.type()).containsExactly(SpanType.TRANSFORM);
    }

    @Test
    void recordsTheErrorOnTheSpanWhenTheTransformThrows() {
        ChatAnswerStreamer streamer = streamer();
        when(queryTransformer.transformWithStatus(anyString(), any())).thenThrow(new IllegalStateException("변환 오류"));

        streamer.startAsync(1L, 99L, "로밍 요금 알려줘", null, null, null);

        verify(persistence).markFailed(99L, "변환 오류");
        ArgumentCaptor<TraceRecord> captor = ArgumentCaptor.forClass(TraceRecord.class);
        verify(traceRepository).save(captor.capture());
        assertThat(captor.getValue().spans().get(0).history()).containsKey("error");
    }

    @Test
    void doesNotChangeTheAnswerResultWhenSavingTheTraceFails() {
        ChatAnswerStreamer streamer = streamer();
        when(traceRepository.save(any())).thenThrow(new IllegalStateException("db down"));

        streamer.startAsync(1L, 99L, "로밍 요금 알려줘", null, null, null);

        verify(persistence, never()).markFailed(any(), anyString());
    }
}
