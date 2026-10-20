package com.vita.chat.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import com.vita.chat.dto.ChatAction;
import com.vita.chat.dto.ChatPlanCard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.vita.chat.UnresolvedReason;
import com.vita.chat.client.LlmStreamClient;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.chat.dto.StreamResult;
import com.vita.chat.entity.ChatEvents;
import com.vita.chat.entity.ChatEvents.AssistantStatus;
import com.vita.chat.service.ChatContextBuilder.ChatContext;
import com.vita.chat.service.ChatStoreFinder.StoreLookup;
import com.vita.chat.service.QueryTransformer.TransformOutcome;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.PlanReference;
import com.vita.search.pipeline.RetrievalResult;
import com.vita.search.pipeline.RetrievalSpanHistory;
import com.vita.search.pipeline.StageTimings;
import com.vita.search.service.FaqRetrievalService;
import com.vita.trace.SpanType;
import com.vita.trace.TraceCollector;
import com.vita.trace.TraceRecorder;

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
    private final TraceRecorder traceRecorder;
    private final TraceConfigProvider traceConfigProvider;
    private final Executor executor;

    public ChatAnswerStreamer(
            ChatSseRegistry registry,
            FaqRetrievalService faqRetrievalService,
            QueryTransformer queryTransformer,
            ChatContextBuilder chatContextBuilder,
            ChatStoreFinder chatStoreFinder,
            LlmStreamClient llmStreamClient,
            ChatMessagePersistence chatMessagePersistence,
            TraceRecorder traceRecorder,
            TraceConfigProvider traceConfigProvider,
            @Qualifier("chatStreamExecutor") Executor executor) {
        this.registry = registry;
        this.faqRetrievalService = faqRetrievalService;
        this.queryTransformer = queryTransformer;
        this.chatContextBuilder = chatContextBuilder;
        this.chatStoreFinder = chatStoreFinder;
        this.llmStreamClient = llmStreamClient;
        this.chatMessagePersistence = chatMessagePersistence;
        this.traceRecorder = traceRecorder;
        this.traceConfigProvider = traceConfigProvider;
        this.executor = executor;
    }

    /** POST /messages에서 PENDING 메시지 저장 후 호출. 즉시 리턴하고 실제 작업은 비동기로 실행 */
    public void startAsync(Long sessionId, Long messageId, String question,
                           BigDecimal lat, BigDecimal lng, String history) {
        executor.execute(() -> run(sessionId, messageId, question, lat, lng, history));
    }

    private void run(Long sessionId, Long messageId, String question,
                     BigDecimal lat, BigDecimal lng, String history) {
        // 처리 과정 기록(trace). 저장은 맨 끝에서 비동기로 맡기고, 기록 때문에 답변이 막히지 않는다.
        TraceCollector trace = traceRecorder.start(messageId);
        // 이 질문을 처리할 때의 설정(임계값, 모델, 프롬프트 해시)도 함께 남겨 시기별 결과를 비교할 수 있게 한다.
        // 기록을 끈 상태면 만들지 않고, 만들다 실패해도 답변은 계속한다.
        if (traceRecorder.enabled()) {
            try {
                traceConfigProvider.snapshot(TOP_K).forEach(trace::putConfig);
            } catch (RuntimeException e) {
                log.warn("trace 설정 스냅샷을 만들지 못했습니다(답변에는 영향 없음). 원인={}", e.getClass().getSimpleName());
            }
        }
        try {
            // 1) 질문 변환 (FAQ용 / 요금제용)
            publishStatus(sessionId, messageId, AssistantStatus.THINKING);
            TraceCollector.Span transformSpan = trace.begin(SpanType.TRANSFORM);
            TransformOutcome transformed;
            try {
                transformed = queryTransformer.transformWithStatus(question, history);
            } catch (RuntimeException e) {
                transformSpan.fail(e, Map.of("question", question));
                throw e;
            }
            transformSpan.end(safeHistory(() -> TransformSpanHistory.of(question, history, transformed)));
            QueryTransformResult q = transformed.result();
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
                recordSkippedRetrieval(trace, "DB_ONLY");
            } else if (storeOnly) {
                log.info("매장 질문: FAQ/요금제 검색 생략, 매장 조회만 사용 - question={}", question);
                retrieved = Retrieved.EMPTY;
                recordSkippedRetrieval(trace, "STORE_ONLY");
            } else if (planOnly) {
                log.info("정형(조건) 질문: FAQ 검색 생략, 요금제만 검색 - question={}", question);
                retrieved = retrieve(trace, question, null, q.planQuery(), "PLAN_ONLY", null);
            } else {
                retrieved = retrieve(trace, question, q.faqQuery(), q.faqSubQueries(), q.planQuery(), null, null);
            }

            // 3) context 조립 (극값 분기는 원문 질문 기준)
            ChatContext context = buildContext(trace, question, retrieved, q, dbOnly, false);

            // DB 결과가 비면(해당 대상의 요금제가 없는 경우 등) 일반 검색으로 폴백
            if (dbOnly && context.text().isBlank()) {
                log.info("정형(극값) 질문이지만 DB 결과 없음, 일반 검색으로 폴백 - question={}", question);
                retrieved = retrieve(trace, question, q.faqQuery(), q.planQuery(), null, "dbOnly");
                context = buildContext(trace, question, retrieved, q, dbOnly, true);
            }

            // 매장 질문이면 BE5 매장 조회 결과를 context 뒤에 붙인다 (실패해도 답변은 계속)
            StoreLookup stores = findStores(trace, q, lat, lng, question);
            String contextText = context.text() + stores.text();

            // 미해결 질문 자동 감지. 판정에 쓰는 값은 모두 답변 생성 전에 정해져 있어서, 생성 span에도 남기려고 먼저 계산한다.
            UnresolvedReason unresolved = UnresolvedReason.judge(
                    !q.storeIntent().isNone(), !context.text().isBlank(), retrieved.topSimilarity());
            String unresolvedName = unresolved == null ? null : unresolved.name();
            Double topSimilarity = retrieved.topSimilarity();

            // 4) 답변 생성 (원문 질문으로 호출)
            publishStatus(sessionId, messageId, AssistantStatus.GENERATING);
            StringBuilder full = new StringBuilder();
            GenerationSpanHistory.Progress progress = new GenerationSpanHistory.Progress();
            TraceCollector.Span generationSpan = trace.begin(SpanType.GENERATION);
            StreamResult streamed;
            try {
                streamed = llmStreamClient.stream(question, contextText, history, delta -> {
                    progress.onDelta();
                    full.append(delta);
                    registry.publish(sessionId, ChatEvents.ASSISTANT_DELTA,
                            new ChatEvents.Delta(sessionId, messageId, delta));
                });
            } catch (RuntimeException e) {
                // 중간에 끊겨도 그때까지 받은 답변 조각과 보낸 context는 남긴다.
                generationSpan.fail(e, safeHistory(() -> GenerationSpanHistory.of(question, contextText, history,
                        full.toString(), progress, null, unresolvedName, topSimilarity)));
                throw e;
            }
            generationSpan.end(safeHistory(() -> GenerationSpanHistory.of(question, contextText, history, full.toString(),
                    progress, streamed, unresolvedName, topSimilarity)));

            List<Long> faqIds = context.faqs().stream().map(FaqReference::faqId).toList();

            // DB 저장을 먼저 하고 done 발행: FE가 done 직후 재조회해도 COMPLETED로 보이게
            // 매장 지도가 있으면 지도 화면으로 가는 버튼을 함께 보낸다
            List<ChatAction> actions = stores.storeMap() != null ? List.of(ChatAction.storeMap()) : List.of();
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
        } finally {
            // 성공이든 실패든 지금까지 모은 기록을 저장한다. 저장 실패는 이 안에서 삼켜진다.
            traceRecorder.saveAsync(trace);
        }
    }
    
    /** @param planNotice 요금제 전체 목록 질문에서 검색(BE3)이 붙인 안내 한 줄. 일반 질문이면 null */
    private record Retrieved(List<FaqReference> faqs, List<PlanReference> plans, Double topSimilarity, String planNotice) {
        static final Retrieved EMPTY = new Retrieved(List.of(), List.of(), null, null);
    }

    /**
     * 변환된 쿼리로 검색한다. faqQuery가 null이면 FAQ 검색은 생략된다 (BE3 4인자 search).
     * 원문(question)은 그대로 넘기므로 무관 질문 규칙과 요금제 조건 추출은 영향받지 않는다.
     * 검색 과정은 faq_retrieve, plan_retrieve 두 span으로 기록한다.
     *
     * @param faqSkipReason FAQ 검색을 이쪽에서 일부러 생략하는 이유(조건만 있는 정형 질문이면 PLAN_ONLY). 아니면 null
     * @param fallbackOf    극값 조회가 비어 일반 검색으로 넘어온 경우 원래 경로 이름(dbOnly). 처음부터 검색한 경우는 null
     */
    private Retrieved retrieve(TraceCollector trace, String question, String faqQuery, String planQuery,
                               String faqSkipReason, String fallbackOf) {
        return retrieve(trace, question, faqQuery, List.of(), planQuery, faqSkipReason, fallbackOf);
    }

    private Retrieved retrieve(TraceCollector trace, String question, String faqQuery, List<String> faqSubQueries,
                               String planQuery, String faqSkipReason, String fallbackOf) {
        log.info("검색 질의 - original={}, faqQuery={}, faqSubQueries={}, planQuery={}", question, faqQuery, faqSubQueries, planQuery);

        TraceCollector.Span faqSpan = trace.begin(SpanType.FAQ_RETRIEVE);
        TraceCollector.Span planSpan = trace.begin(SpanType.PLAN_RETRIEVE);
        LocalDateTime searchStart = trace.now();
        RetrievalResult result;
        try {
            result = faqSubQueries.isEmpty()
                    ? faqRetrievalService.searchWithDetails(question, faqQuery, planQuery, TOP_K)
                    : faqRetrievalService.searchWithDetails(question, faqQuery, faqSubQueries, planQuery, TOP_K);
        } catch (RuntimeException e) {
            Map<String, Object> partial = new LinkedHashMap<>();
            partial.put("query", faqQuery);
            faqSpan.fail(e, partial);
            planSpan.fail(e, Map.of("query", planQuery == null ? "" : planQuery));
            throw e;
        }
        LocalDateTime searchEnd = trace.now();

        Map<String, Object> faqHistory = safeHistory(() -> {
            Map<String, Object> built = RetrievalSpanHistory.faq(result);
            if (built == null) {
                built = RetrievalSpanHistory.skipped("NO_DETAILS");
            } else if (faqQuery == null && faqSkipReason != null) {
                built = RetrievalSpanHistory.skipped(faqSkipReason);
            }
            return fallbackOf == null ? built : withFallback(built, fallbackOf);
        });
        Map<String, Object> planHistory = safeHistory(() -> {
            Map<String, Object> built = RetrievalSpanHistory.plan(result);
            if (built == null) {
                built = RetrievalSpanHistory.skipped("NO_DETAILS");
            }
            return fallbackOf == null ? built : withFallback(built, fallbackOf);
        });

        // 검색은 한 번의 호출이지만 안에서 임베딩 -> FAQ 검색 -> 요금제 검색 순으로 일어난다. 두 span이 같은 구간을 갖지 않도록,
        // 단계별 시간(StageTimings)으로 FAQ 쪽(임베딩 + FAQ 검색)과 요금제 쪽(요금제 검색)의 구간을 나눠 기록한다.
        StageTimings timings = result.timings();
        long faqNanos = timings == null ? 0 : timings.embeddingNanos() + timings.faqSearchNanos();
        long planNanos = timings == null ? 0 : timings.planSearchNanos();
        LocalDateTime faqEnd = earlier(searchEnd, searchStart.plusNanos(faqNanos));
        LocalDateTime planEnd = earlier(searchEnd, faqEnd.plusNanos(planNanos));
        faqSpan.endAt(searchStart, faqEnd, faqHistory);
        planSpan.endAt(faqEnd, planEnd, planHistory);

        FaqRetrievalContext c = result.context();
        List<FaqReference> faqs = (faqQuery != null && c.hasRelevantFaq()) ? c.references() : List.of();
        List<PlanReference> plans = (planQuery != null) ? c.planReferences() : List.of();
        String planNotice = (planQuery != null) ? c.planNotice() : null;
        return new Retrieved(faqs, plans, c.topSimilarity(), planNotice);
    }

    /**
     * context를 조립한다. 극값 질문이면 이 안에서 DB 조회가 일어나므로 그 조회를 plan_lookup span으로 기록한다.
     *
     * @param dbOnly        극값 질문이라 검색을 건너뛰고 조회 결과만 쓰는 경로인지
     * @param afterFallback 조회 결과가 비어 일반 검색을 한 뒤 다시 조립하는 것인지
     */
    private ChatContext buildContext(TraceCollector trace, String question, Retrieved retrieved, QueryTransformResult q,
                                     boolean dbOnly, boolean afterFallback) {
        TraceCollector.Span lookupSpan = q.planIntent().extreme() ? trace.begin(SpanType.PLAN_LOOKUP) : null;
        ChatContext context;
        try {
            context = chatContextBuilder.build(
                    question, retrieved.faqs(), retrieved.plans(), q.planIntent(), q.priceRange(), q.dataRange(),
                    retrieved.planNotice());
        } catch (RuntimeException e) {
            if (lookupSpan != null) {
                lookupSpan.fail(e, Map.of("sort_key", String.valueOf(q.planIntent().sortKey())));
            }
            throw e;
        }
        if (lookupSpan != null) {
            // 처음 조립에서 조회 결과가 비면 곧바로 일반 검색으로 넘어간다
            boolean fallbackToSearch = dbOnly && !afterFallback && context.text().isBlank();
            lookupSpan.end(safeHistory(() -> context.extremeLookup() == null
                    ? RetrievalSpanHistory.skipped("NO_DETAILS")
                    : PlanLookupSpanHistory.of(context.extremeLookup(), fallbackToSearch, afterFallback)));
        }
        return context;
    }

    /** 매장 질문이면 매장을 조회하고 store_lookup span으로 기록한다. 위치 좌표는 기록하지 않고 위치를 받았는지만 남긴다. */
    private StoreLookup findStores(TraceCollector trace, QueryTransformResult q, BigDecimal lat, BigDecimal lng, String question) {
        TraceCollector.Span storeSpan = q.storeIntent().isNone() ? null : trace.begin(SpanType.STORE_LOOKUP);
        StoreLookup stores;
        try {
            stores = chatStoreFinder.find(q.storeIntent(), lat, lng, question);
        } catch (RuntimeException e) {
            if (storeSpan != null) {
                storeSpan.fail(e, TransformSpanHistory.storeIntentOnly(q.storeIntent()));
            }
            throw e;
        }
        if (storeSpan != null) {
            storeSpan.end(safeHistory(() -> StoreLookupSpanHistory.of(q.storeIntent(), lat != null && lng != null, stores)));
        }
        return stores;
    }

    /**
     * 단계 기록(history)을 만든다. 기록은 부가 기능이라, 기록을 끈 상태면 만들지 않고, 만들다 예외가 나도 답변을 막지 않고
     * 어떤 예외였는지만 남긴다.
     */
    private Map<String, Object> safeHistory(Supplier<Map<String, Object>> builder) {
        if (!traceRecorder.enabled()) {
            return Map.of();
        }
        try {
            Map<String, Object> built = builder.get();
            return built == null ? Map.of() : built;
        } catch (RuntimeException e) {
            log.warn("trace 단계 기록을 만들지 못했습니다(답변에는 영향 없음). 원인={}", e.getClass().getSimpleName());
            return Map.of("history_error", e.getClass().getSimpleName());
        }
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    /** 검색을 이쪽에서 건너뛴 경로(극값 조회만 / 매장 조회만)를 FAQ·요금제 span에 사유와 함께 남긴다. */
    private static void recordSkippedRetrieval(TraceCollector trace, String reason) {
        trace.begin(SpanType.FAQ_RETRIEVE).end(RetrievalSpanHistory.skipped(reason));
        trace.begin(SpanType.PLAN_RETRIEVE).end(RetrievalSpanHistory.skipped(reason));
    }

    /** 일반 검색으로 넘어온 span임을 표시한다(처음 시도한 경로 이름을 fallback_of에 남긴다). */
    private static Map<String, Object> withFallback(Map<String, Object> history, String fallbackOf) {
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("fallback_of", fallbackOf);
        copy.putAll(history);
        return copy;
    }

    private void publishStatus(Long sessionId, Long messageId, AssistantStatus status) {
        registry.publish(sessionId, ChatEvents.ASSISTANT_STATUS,
                new ChatEvents.Status(sessionId, messageId, status));
    }
}