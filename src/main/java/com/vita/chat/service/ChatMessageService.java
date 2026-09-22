package com.vita.chat.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.vita.chat.ChatMessageRole;
import com.vita.chat.ChatMessageStatus;
import com.vita.chat.client.BedrockChatClient;
import com.vita.chat.dto.ChatMessageResponse;
import com.vita.chat.dto.ChatMessageSendRequest;
import com.vita.chat.dto.ChatSessionListResponse;
import com.vita.chat.dto.ChatSessionSummaryResponse;
import com.vita.chat.dto.MessageResponse;
import com.vita.chat.dto.SessionMessagesResponse;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.ChatSession;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.ChatSessionRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.service.FaqRetrievalService;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageService {

	private static final int TOP_K = 3; // 검색해올 FAQ 후보 개수 — threshold 필터는 BE3 쪽에서 처리됨
	
	private final ChatMessageRepository chatMessageRepository;
	private final ChatSessionRepository chatSessionRepository;      
	private final BedrockChatClient bedrockChatClient;
	private final FaqRetrievalService faqRetrievalService;
	
	@Transactional
	public ChatMessageResponse sendMessage(Long sessionId, ChatMessageSendRequest request) {
		ChatSession session = chatSessionRepository.findById(sessionId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "세션을 찾을 수 없습니다."));
		
		// 1) 사용자 질문 저장
		ChatMessage userMessage = ChatMessage.builder()
				.session(session)
				.role(ChatMessageRole.USER)
				.content(request.content())
				.status(ChatMessageStatus.COMPLETED)
				.build();
		chatMessageRepository.save(userMessage);
		
		
		// 2) 조립 재료 준비
		String context = buildContext(request.content()); // 파라미터 수정 필요
		String conversationHistory = buildConversationHistory(sessionId);
		
		log.info("service context: " + context);
		
		// 3) 어시스턴트 메시지(PENDING)로 먼저 저장
		ChatMessage assistantMessage = ChatMessage.builder()
				.session(session)
				.role(ChatMessageRole.ASSISTANT)
				.content(context)
				.status(ChatMessageStatus.PENDING)
				.build();
		chatMessageRepository.save(assistantMessage);
		
		long startTime = System.currentTimeMillis();
		
		try {
			String answer = bedrockChatClient.ask(request.content(), context, conversationHistory);
			
			
			assistantMessage.markCompleted(answer);
		} catch(Exception e) {
			log.error("AI 응답 생성 실패 - sessionId: {}", sessionId, e);  // 마지막 인자로 e를 넘기면 SLF4J가 스택 트레이스 전체를 출력해줌
			assistantMessage.markFailed();
		}
		long latencyMs = System.currentTimeMillis() - startTime;

        session.update();
        
		return ChatMessageResponse.of(assistantMessage, latencyMs);
		
	}
	
	private String buildContext(String query) {
		FaqRetrievalContext retrievalContext = faqRetrievalService.search(query, TOP_K);
		
		if (!retrievalContext.hasRelevantFaq()) {
			log.info("관련 FAQ 없음 (topSimilarity={}). query={}", retrievalContext.topSimilarity(), query);
			return "";
		}
 
		return retrievalContext.references().stream()
				.map(faq -> """
						<document>
						<category>%s / %s</category>
						<question>%s</question>
						<answer>%s</answer>
						</document>
						""".formatted(faq.category(), faq.subcategory(), faq.question(), faq.answer()))
				.collect(Collectors.joining("\n"));
	}
	
	private String buildConversationHistory (Long sessionId) {
		
		List<ChatMessage> previousMessages =
				chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
		
		return previousMessages.stream()
				.filter(m -> m.getStatus() == ChatMessageStatus.COMPLETED)
				.map(m -> "%s: %s".formatted(m.getRole(), m.getContent()))
				.collect(Collectors.joining("\n"));
		
	}
	
	public SessionMessagesResponse getMessages(Long sessionId, Long userId) {
		
		ChatSession session = chatSessionRepository.findById(sessionId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 세션입니다."));
		
		if(!session.getUserId().equals(userId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "타인의 세션에는 접근할 수 없습니다.");
		}
		List<MessageResponse> messages = chatMessageRepository
				.findAllBySession_IdOrderByCreatedAtAsc(sessionId)
				.stream()
				.map(MessageResponse::from)
				.toList();
		
		return new SessionMessagesResponse(sessionId, messages);
	}
}
