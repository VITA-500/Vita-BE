package com.vita.chat.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executor;

import com.vita.chat.dto.ChatAction;
import com.vita.chat.dto.ChatPlanCard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.vita.chat.UnresolvedReason;
import com.vita.chat.client.LlmStreamClient;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.chat.entity.ChatEvents;
import com.vita.chat.entity.ChatEvents.AssistantStatus;
import com.vita.chat.service.ChatContextBuilder.ChatContext;
import com.vita.chat.service.ChatStoreFinder.StoreLookup;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.PlanReference;
import com.vita.search.service.FaqRetrievalService;

import lombok.extern.slf4j.Slf4j;

/**
 * 답변 생성 파이프라인을 별도 스레드에서 실행하며 SSE 이벤트를 발행.
 * thinking(질문 변환) -> retrieving_faq(검색) -> generating -> (delta 반복) -> done / error
 */
@Slf4j
@Service
public class ChatAnswerStreamer {

    private static final int TOP_K = 10;

    private final ChatSseRegistry registry;
    private final FaqRetrievalService faqRetrievalService;
    private final QueryTransformer queryTransformer;
    private final ChatContextBuilder chatContextBuilder;
    private final ChatStoreFinder chatStoreFinder;
    private final LlmStreamClient llmStreamClient;
    private final ChatMessagePersistence chatMessagePersistence;
    private final Executor executor;

    public ChatAnswerStreamer(
            ChatSseRegistry registry,
            FaqRetrievalService faqRetrievalService,
            QueryTransformer queryTransformer,
            ChatContextBuilder chatContextBuilder,
            ChatStoreFinder chatStoreFinder,
            LlmStreamClient llmStreamClient,
            ChatMessagePersistence chatMessagePersistence,
            @Qualifier("chatStreamExecutor") Executor executor) {
        this.registry = registry;
        this.faqRetrievalService = faqRetrievalService;
        this.queryTransformer = queryTransformer;
        this.chatContextBuilder = chatContextBuilder;
        this.chatStoreFinder = chatStoreFinder;
        this.llmStreamClient = llmStreamClient;
        this.chatMessagePersistence = chatMessagePersistence;
        this.executor = executor;
    }

    /** POST /messages에서 PENDING 메시지 저장 후 호출. 즉시 리턴하고 실제 작업은 비동기로 실행 */
    public void startAsync(Long sessionId, Long messageId, String question,
                           BigDecimal lat, BigDecimal lng, String history) {
        executor.execute(() -> run(sessionId, messageId, question, lat, lng, history));
    }

    private void run(Long sessionId, Long messageId, String question,
                     BigDecimal lat, BigDecimal lng, String history) {
        try {
            // 1) 질문 변환 (FAQ용 / 요금제용)
            publishStatus(sessionId, messageId, AssistantStatus.THINKING);
            QueryTransformResult q = queryTransformer.transform(question, history);
            log.info("query transform - original={}, faqQuery={}, planQuery={}, extreme={}, sortKey={}, limit={}, structured={}, priceRange={}, dataRange={}",
                    question, q.faqQuery(), q.planQuery(),
                    q.planIntent().extreme(), q.planIntent().sortKey(), q.planIntent().limit(), q.structured(), q.priceRange(), q.dataRange());
            log.info("store intent - question={}, storeIntent={}", question, q.storeIntent());

            // 2) 검색 (정형 질문은 DB 조회만 사용하고 벡터 검색을 생략)
            publishStatus(sessionId, messageId, AssistantStatus.RETRIEVING_FAQ);

            // 정형 질문 = 요금제 극값 조회로 끝나는 질문 (FAQ/벡터 검색 불필요)
            boolean extreme = q.planIntent().extreme();
            boolean dbOnly = q.structured() && extreme;                                   // 극값: DB 조회만
            boolean planOnly = q.structured() && !extreme && q.planQuery() != null;       // 조건만 있는 정형: 요금제만 검색
            boolean storeOnly = !q.storeIntent().isNone() && q.faqQuery() == null && q.planQuery() == null; // 매장 질문: 매장 조회만

            Retrieved retrieved;
            if (dbOnly) {
                log.info("정형(극값) 질문: 검색 생략, DB 조회 결과만 사용 - question={}", question);
                retrieved = Retrieved.EMPTY;
            } else if (storeOnly) {
                log.info("매장 질문: FAQ/요금제 검색 생략, 매장 조회만 사용 - question={}", question);
                retrieved = Retrieved.EMPTY;
            } else if (planOnly) {
                log.info("정형(조건) 질문: FAQ 검색 생략, 요금제만 검색 - question={}", question);
                retrieved = retrieve(question, null, q.planQuery());
            } else {
                retrieved = retrieve(question, q.faqQuery(), q.planQuery());
            }

            // 3) context 조립 (극값 분기는 원문 질문 기준)
            ChatContext context = chatContextBuilder.build(
                    question, retrieved.faqs(), retrieved.plans(), q.planIntent(), q.priceRange(), q.dataRange(),
                    retrieved.planNotice());

            // DB 결과가 비면(해당 대상의 요금제가 없는 경우 등) 일반 검색으로 폴백
            if (dbOnly && context.text().isBlank()) {
                log.info("정형(극값) 질문이지만 DB 결과 없음, 일반 검색으로 폴백 - question={}", question);
                retrieved = retrieve(question, q.faqQuery(), q.planQuery());
                context = chatContextBuilder.build(
                        question, retrieved.faqs(), retrieved.plans(), q.planIntent(), q.priceRange(), q.dataRange(),
                        retrieved.planNotice());
            }

            // 매장 질문이면 BE5 매장 조회 결과를 context 뒤에 붙인다 (실패해도 답변은 계속)
            StoreLookup stores = chatStoreFinder.find(q.storeIntent(), lat, lng, question);
            String contextText = context.text() + stores.text();

            // 4) 답변 생성 (원문 질문으로 호출)
            publishStatus(sessionId, messageId, AssistantStatus.GENERATING);
            StringBuilder full = new StringBuilder();
            llmStreamClient.stream(question, contextText, history, delta -> {
                full.append(delta);
                registry.publish(sessionId, ChatEvents.ASSISTANT_DELTA,
                        new ChatEvents.Delta(sessionId, messageId, delta));
            });

            List<Long> faqIds = context.faqs().stream().map(FaqReference::faqId).toList();

            // DB 저장을 먼저 하고 done 발행: FE가 done 직후 재조회해도 COMPLETED로 보이게
            // 매장 지도가 있으면 지도 화면으로 가는 버튼을 함께 보낸다
            List<ChatAction> actions = stores.storeMap() != null ? List.of(ChatAction.storeMap()) : List.of();
            // 미해결 질문 자동 감지
            UnresolvedReason unresolved = UnresolvedReason.judge(
                    !q.storeIntent().isNone(), !context.text().isBlank(), retrieved.topSimilarity());
            if (unresolved != null) {
                log.info("미해결 질문 - question={}, reason={}, topSimilarity={}", question, unresolved, retrieved.topSimilarity());
            }
            // 요금제 카드: LLM에 넘긴 요금제 중 답변에 이름이 나온 것만 (글과 카드가 어긋나지 않게)
            List<ChatPlanCard> plans = ChatPlanCard.mentionedIn(full.toString(), context.plans());
            chatMessagePersistence.markCompleted(messageId, full.toString(), faqIds, stores.storeMap(), plans, actions,
                    unresolved, retrieved.topSimilarity());
            registry.publish(sessionId, ChatEvents.ASSISTANT_DONE,
                    new ChatEvents.Done(sessionId, messageId, full.toString(), stores.storeMap(), plans, actions));

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
    
    /** @param planNotice 요금제 전체 목록 질문에서 검색(BE3)이 붙인 안내 한 줄. 일반 질문이면 null */
    private record Retrieved(List<FaqReference> faqs, List<PlanReference> plans, Double topSimilarity, String planNotice) {
        static final Retrieved EMPTY = new Retrieved(List.of(), List.of(), null, null);
    }

    /**
     * 변환된 쿼리로 검색한다. faqQuery가 null이면 FAQ 검색은 생략된다 (BE3 4인자 search).
     * 원문(question)은 그대로 넘기므로 무관 질문 규칙과 요금제 조건 추출은 영향받지 않는다.
     */
    private Retrieved retrieve(String question, String faqQuery, String planQuery) {
        log.info("검색 질의 - original={}, faqQuery={}, planQuery={}", question, faqQuery, planQuery);

        FaqRetrievalContext c = faqRetrievalService.search(question, faqQuery, planQuery, TOP_K);
        List<FaqReference> faqs = (faqQuery != null && c.hasRelevantFaq()) ? c.references() : List.of();
        List<PlanReference> plans = (planQuery != null) ? c.planReferences() : List.of();
        String planNotice = (planQuery != null) ? c.planNotice() : null;
        return new Retrieved(faqs, plans, c.topSimilarity(), planNotice);
    }

    private void publishStatus(Long sessionId, Long messageId, AssistantStatus status) {
        registry.publish(sessionId, ChatEvents.ASSISTANT_STATUS,
                new ChatEvents.Status(sessionId, messageId, status));
    }
}