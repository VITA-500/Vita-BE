package com.vita.chat.service;

import java.util.List;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vita.chat.client.LlmStreamClient;
import com.vita.chat.entity.ChatEvents;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.ChatMessageFaqRef;
import com.vita.chat.entity.ChatEvents.AssistantStatus;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.service.FaqRetrievalService;

import lombok.extern.slf4j.Slf4j;

/**
 * 답변 생성 파이프라인을 별도 스레드에서 실행하며 SSE 이벤트를 발행한다.
 * thinking -> retrieving_faq -> generating -> (delta 반복) -> done / error
 */
@Slf4j
@Service
public class ChatAnswerStreamer {

    private static final int FAQ_TOP_K = 5; // TODO: 기존 TOP_K 값 사용

    private final ChatSseRegistry registry;
    private final FaqRetrievalService faqRetrievalService;
    private final LlmStreamClient llmStreamClient;
    private final ChatMessagePersistence chatMessagePersistence; // 기존 서비스 (markCompleted / markFailed 보유)
    private final Executor executor;

    public ChatAnswerStreamer(
            ChatSseRegistry registry,
            FaqRetrievalService faqRetrievalService,
            LlmStreamClient llmStreamClient,
            ChatMessagePersistence chatMessagePersistence,
            @Qualifier("chatStreamExecutor") Executor executor) {
        this.registry = registry;
        this.faqRetrievalService = faqRetrievalService;
        this.llmStreamClient = llmStreamClient;
        this.chatMessagePersistence = chatMessagePersistence;
        this.executor = executor;
    }

    /** POST /messages에서 PENDING 메시지 저장 후 호출. 즉시 리턴하고 실제 작업은 비동기로 실행 */
    public void startAsync(Long sessionId, Long messageId, String question) {
//    	log.info("🔥 startAsync 호출: sessionId={}, messageId={}", sessionId, messageId);
    	
        executor.execute(() -> run(sessionId, messageId, question));
    }

    private void run(Long sessionId, Long messageId, String question) {
        try {
//        	log.info("🔥 streamer run 시작: sessionId={}, messageId={}", sessionId, messageId);
        	
            publishStatus(sessionId, messageId, AssistantStatus.THINKING);
            // TODO: 질문 재작성(faq_query / plan_query)이 들어가면 이 단계에서 수행

//            log.info("🔥 THINKING 발행 완료");
            
            publishStatus(sessionId, messageId, AssistantStatus.RETRIEVING_FAQ);
            
//            log.info("🔥 RETRIEVING_FAQ 발행 완료");
            
            FaqRetrievalContext faqContext = faqRetrievalService.search(question, FAQ_TOP_K);
            String contextText = buildContextText(faqContext);
            String history = loadConversationHistory(sessionId);

            publishStatus(sessionId, messageId, AssistantStatus.GENERATING);
            StringBuilder full = new StringBuilder();
            llmStreamClient.stream(question, contextText, history, delta -> {
                full.append(delta);
                registry.publish(sessionId, ChatEvents.ASSISTANT_DELTA,
                        new ChatEvents.Delta(sessionId, messageId, delta));
            });
            
         // 답변 근거로 사용한 FAQ id 목록 (chat_message_faq_ref 저장용)
            List<Long> faqIds = faqContext.references().stream()
                    .map(ref -> ref.faqId())
                    .toList();


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
                // 실패 기록 저장이 또 실패해도 FE에게 error 이벤트는 반드시 보낸다
                log.error("FAILED 상태 저장 실패. messageId={}", messageId, persistError);
            }
            registry.publish(sessionId, ChatEvents.ASSISTANT_ERROR,
                    new ChatEvents.Error(sessionId, messageId, "INTERNAL_ERROR", "답변 생성에 실패했습니다."));
        }
    }
  
    /**
     * TODO: 기존 sendMessage에서 bedrockChatClient.ask(...) 호출 전에 context 문자열을 만들던 로직으로 교체.
     * (<plan>, <comparison_result> 등 요금제 context 포함). 아래는 FAQ만 넣은 임시 구현이다.
     */
    private String buildContextText(FaqRetrievalContext faqContext) {
        StringBuilder sb = new StringBuilder();
        faqContext.references().forEach(ref -> sb.append("<document>\n")
                .append("Q: ").append(ref.question()).append("\n")
                .append("A: ").append(ref.answer()).append("\n")
                .append("</document>\n"));
        return sb.toString();
    }

    /** TODO: 기존 대화 이력 조회/포맷 로직으로 교체. 지금은 이력 없이 호출된다. */
    private String loadConversationHistory(Long sessionId) {
        return "";
    }

    private void publishStatus(Long sessionId, Long messageId, AssistantStatus status) {
        registry.publish(sessionId, ChatEvents.ASSISTANT_STATUS,
                new ChatEvents.Status(sessionId, messageId, status));
    }
}