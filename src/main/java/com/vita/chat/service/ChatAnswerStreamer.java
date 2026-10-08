package com.vita.chat.service;

import java.util.List;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    private final QueryTransformer queryTransformer;
    private final QuestionTranslator questionTranslator;
    private final ChatContextBuilder chatContextBuilder;
    private final LlmStreamClient llmStreamClient;
    private final ChatMessagePersistence chatMessagePersistence;
    private final Executor executor;
    /** 실험 B: true면 검색 질의를 원문의 영어 번역문으로 바꿔서 검색한다. 기본 false(기존 동작). */
    private final boolean translateEnabled;

    public ChatAnswerStreamer(
            ChatSseRegistry registry,
            FaqRetrievalService faqRetrievalService,
            QueryTransformer queryTransformer,
            QuestionTranslator questionTranslator,
            ChatContextBuilder chatContextBuilder,
            LlmStreamClient llmStreamClient,
            ChatMessagePersistence chatMessagePersistence,
            @Qualifier("chatStreamExecutor") Executor executor,
            @Value("${vita.experiment.translate-question:false}") boolean translateEnabled) {
        this.registry = registry;
        this.faqRetrievalService = faqRetrievalService;
        this.queryTransformer = queryTransformer;
        this.questionTranslator = questionTranslator;
        this.chatContextBuilder = chatContextBuilder;
        this.llmStreamClient = llmStreamClient;
        this.chatMessagePersistence = chatMessagePersistence;
        this.executor = executor;
        this.translateEnabled = translateEnabled;
    }

    /** POST /messages에서 PENDING 메시지 저장 후 호출. 즉시 리턴하고 실제 작업은 비동기로 실행 */
    public void startAsync(Long sessionId, Long messageId, String question, String history) {
        executor.execute(() -> run(sessionId, messageId, question, history));
    }

    private void run(Long sessionId, Long messageId, String question, String history) {
    	log.info("실험 플래그 - translateEnabled={}", translateEnabled);
        try {
            // 1) 질문 변환 (FAQ용 / 요금제용)
            publishStatus(sessionId, messageId, AssistantStatus.THINKING);
            QueryTransformResult q = queryTransformer.transform(question, history);
            log.info("query transform - original={}, faqQuery={}, planQuery={}, extreme={}, sortKey={}, limit={}, structured={}, priceRange={}, dataRange={}",
                    question, q.faqQuery(), q.planQuery(),
                    q.planIntent().extreme(), q.planIntent().sortKey(), q.planIntent().limit(), q.structured(), q.priceRange(), q.dataRange());

          // 2) 검색 (정형 질문은 DB 조회만 사용하고 벡터 검색을 생략)
            publishStatus(sessionId, messageId, AssistantStatus.RETRIEVING_FAQ);

            // 정형 질문 = 요금제 극값 조회로 끝나는 질문 (FAQ/벡터 검색 불필요)
            boolean extreme = q.planIntent().extreme();
            boolean dbOnly = q.structured() && extreme;                                   // 극값: DB 조회만
            boolean planOnly = q.structured() && !extreme && q.planQuery() != null;       // 조건만 있는 정형: 요금제만 검색

            Retrieved retrieved;
            if (dbOnly) {
                log.info("정형(극값) 질문: 검색 생략, DB 조회 결과만 사용 - question={}", question);
                retrieved = Retrieved.EMPTY;
            } else if (planOnly) {
                log.info("정형(조건) 질문: FAQ 검색 생략, 요금제만 검색 - question={}", question);
                retrieved = retrieve(question, null, q.planQuery(), translateForSearch(question));
            } else {
                retrieved = retrieve(question, q.faqQuery(), q.planQuery(), translateForSearch(question));
            }
            
            // 3) context 조립 (극값 분기는 원문 질문 기준)
            ChatContext context = chatContextBuilder.build(
                    question, retrieved.faqs(), retrieved.plans(), q.planIntent(), q.priceRange(), q.dataRange());

            // DB 결과가 비면(해당 대상의 요금제가 없는 경우 등) 일반 검색으로 폴백
            if (dbOnly && context.text().isBlank()) {
                log.info("정형(극값) 질문이지만 DB 결과 없음, 일반 검색으로 폴백 - question={}", question);
                retrieved = retrieve(question, q.faqQuery(), q.planQuery(), translateForSearch(question));
                context = chatContextBuilder.build(
                        question, retrieved.faqs(), retrieved.plans(), q.planIntent(), q.priceRange(), q.dataRange());
            }
            
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
    
    private record Retrieved(List<FaqReference> faqs, List<PlanReference> plans) {
        static final Retrieved EMPTY = new Retrieved(List.of(), List.of());
    }

    /**
     * 실험 B: 플래그가 켜져 있으면 원문을 영어로 번역한 검색용 질의를 만든다.
     * 꺼져 있거나 번역이 폴백되면 null을 돌려주고, 이때는 기존 변환 질의(faqQuery/planQuery)를 그대로 쓴다.
     */
    private String translateForSearch(String question) {
        if (!translateEnabled) {
            log.info("질문 번역 비활성(vita.experiment.translate-question=false) - 기존 검색 질의 사용");
            return null;
        }
        QuestionTranslator.TranslationOutcome outcome = questionTranslator.translateWithStatus(question);
        if (outcome.fallback()) {
            log.info("질문 번역 폴백 - 기존 검색 질의 사용, reason={}", outcome.fallbackReason());
            return null;
        }
        return outcome.translated();
    }

    /**
     * 변환된 쿼리로 검색한다. faqQuery가 null이면 FAQ 검색은 생략된다 (BE3 4인자 search).
     * translated가 있으면 null이 아닌 쪽 검색 질의를 번역문으로 바꾼다 — 어느 쪽을 검색할지(null 여부)는 바꾸지 않는다.
     * 원문(question)은 그대로 넘기므로 무관 질문 규칙과 요금제 조건 추출은 영향받지 않는다.
     */
    private Retrieved retrieve(String question, String faqQuery, String planQuery, String translated) {
        String faqSearch = (faqQuery != null && translated != null) ? translated : faqQuery;
        String planSearch = (planQuery != null && translated != null) ? translated : planQuery;

        log.info("검색 질의 - original={}, faqQuery={}, planQuery={}, translatedUsed={}",
                question, faqSearch, planSearch, translated != null);

        FaqRetrievalContext c = faqRetrievalService.search(question, faqSearch, planSearch, TOP_K);
        List<FaqReference> faqs = (faqQuery != null && c.hasRelevantFaq()) ? c.references() : List.of();
        List<PlanReference> plans = (planQuery != null) ? c.planReferences() : List.of();
        return new Retrieved(faqs, plans);
    }

    private void publishStatus(Long sessionId, Long messageId, AssistantStatus status) {
        registry.publish(sessionId, ChatEvents.ASSISTANT_STATUS,
                new ChatEvents.Status(sessionId, messageId, status));
    }
}