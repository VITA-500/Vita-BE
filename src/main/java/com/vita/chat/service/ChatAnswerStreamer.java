package com.vita.chat.service;

import java.util.List;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.vita.chat.client.LlmStreamClient;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.chat.entity.ChatEvents;
import com.vita.chat.entity.ChatEvents.AssistantStatus;
import com.vita.chat.service.ChatContextBuilder.ChatContext;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.PlanReference;
import com.vita.search.service.FaqRetrievalService;

import lombok.extern.slf4j.Slf4j;

/**
 * 답변 생성 파이프라인을 별도 스레드에서 실행하며 SSE 이벤트를 발행한다.
 * thinking(질문 변환) -> retrieving_faq(검색) -> generating -> (delta 반복) -> done / error
 */
@Slf4j
@Service
public class ChatAnswerStreamer {

    private static final int TOP_K = 10;

    private final ChatSseRegistry registry;
    private final FaqRetrievalService faqRetrievalService;
    private final LlmQueryTransformer queryTransformer;
    private final ChatContextBuilder chatContextBuilder;
    private final LlmStreamClient llmStreamClient;
    private final ChatMessagePersistence chatMessagePersistence;
    private final Executor executor;

    public ChatAnswerStreamer(
            ChatSseRegistry registry,
            FaqRetrievalService faqRetrievalService,
            LlmQueryTransformer queryTransformer,
            ChatContextBuilder chatContextBuilder,
            LlmStreamClient llmStreamClient,
            ChatMessagePersistence chatMessagePersistence,
            @Qualifier("chatStreamExecutor") Executor executor) {
        this.registry = registry;
        this.faqRetrievalService = faqRetrievalService;
        this.queryTransformer = queryTransformer;
        this.chatContextBuilder = chatContextBuilder;
        this.llmStreamClient = llmStreamClient;
        this.chatMessagePersistence = chatMessagePersistence;
        this.executor = executor;
    }

    /** POST /messages에서 PENDING 메시지 저장 후 호출. 즉시 리턴하고 실제 작업은 비동기로 실행 */
    public void startAsync(Long sessionId, Long messageId, String question, String history) {
        executor.execute(() -> run(sessionId, messageId, question, history));
    }

    private void run(Long sessionId, Long messageId, String question, String history) {
        try {
            // 1) 질문 변환 (FAQ용 / 요금제용)
            publishStatus(sessionId, messageId, AssistantStatus.THINKING);
            QueryTransformResult q = queryTransformer.transform(question, history);
            log.info("query transform - original={}, faqQuery={}, planQuery={}, extreme={}, sortKey={}, limit={}, structured={}, priceRange={}",
                    question, q.faqQuery(), q.planQuery(),
                    q.planIntent().extreme(), q.planIntent().sortKey(), q.planIntent().limit(), q.structured(), q.priceRange());

            // 2) 검색
            publishStatus(sessionId, messageId, AssistantStatus.RETRIEVING_FAQ);
            List<FaqReference> faqs = List.of();
            List<PlanReference> plans = List.of();

            if (q.faqQuery() != null && q.faqQuery().equals(q.planQuery())) {
                // 폴백(원문)처럼 두 쿼리가 같으면 검색 한 번으로 양쪽 결과를 쓴다
                FaqRetrievalContext c = faqRetrievalService.search(q.faqQuery(), TOP_K);
                faqs = c.hasRelevantFaq() ? c.references() : List.of();
                plans = c.planReferences();
            } else {
                if (q.faqQuery() != null) {
                    FaqRetrievalContext c = faqRetrievalService.search(q.faqQuery(), TOP_K);
                    faqs = c.hasRelevantFaq() ? c.references() : List.of();
                }
                if (q.planQuery() != null) {
                    plans = faqRetrievalService.search(q.planQuery(), TOP_K).planReferences();
                }
            }

            // 3) context 조립 (극값 분기는 원문 질문 기준)
            ChatContext context = chatContextBuilder.build(question, faqs, plans, q.planIntent(), q.priceRange());

            // 4) 답변 생성 (원문 질문으로 호출)
            publishStatus(sessionId, messageId, AssistantStatus.GENERATING);
            StringBuilder full = new StringBuilder();
            llmStreamClient.stream(question, context.text(), history, delta -> {
                full.append(delta);
                registry.publish(sessionId, ChatEvents.ASSISTANT_DELTA,
                        new ChatEvents.Delta(sessionId, messageId, delta));
            });

            List<Long> faqIds = context.faqs().stream().map(FaqReference::faqId).toList();

            // DB 저장을 먼저 하고 done 발행: FE가 done 직후 재조회해도 COMPLETED로 보이게
            chatMessagePersistence.markCompleted(messageId, full.toString(), faqIds);
            registry.publish(sessionId, ChatEvents.ASSISTANT_DONE,
                    new ChatEvents.Done(sessionId, messageId, full.toString()));

        } catch (Exception e) {
            log.error("답변 생성 실패. sessionId={}, messageId={}", sessionId, messageId, e);

            String errorMessage = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            try {
                chatMessagePersistence.markFailed(messageId, errorMessage);
            } catch (Exception persistError) {
                log.error("FAILED 상태 저장 실패. messageId={}", messageId, persistError);
            }
            registry.publish(sessionId, ChatEvents.ASSISTANT_ERROR,
                    new ChatEvents.Error(sessionId, messageId, "INTERNAL_ERROR", "답변 생성에 실패했습니다."));
        }
    }

    private void publishStatus(Long sessionId, Long messageId, AssistantStatus status) {
        registry.publish(sessionId, ChatEvents.ASSISTANT_STATUS,
                new ChatEvents.Status(sessionId, messageId, status));
    }
}