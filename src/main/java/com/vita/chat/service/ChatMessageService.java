package com.vita.chat.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vita.chat.ChatMessageStatus;
import com.vita.chat.dto.ChatMessageResponse;
import com.vita.chat.dto.ChatMessageSendRequest;
import com.vita.chat.dto.ChatMessageItemResponse;
import com.vita.chat.dto.SessionMessagesResponse;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.ChatSession;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.ChatSessionRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageService {

	private static final int TOP_K = 3; // 검색해올 FAQ 후보 개수 — threshold 필터는 BE3 쪽에서 처리됨
	private static final int MAX_HISTORY_TURNS = 5;  // 1턴 = USER + ASSISTANT 2개
	
	private final ChatAnswerStreamer answerStreamer;
	private final ChatMessagePersistence persistence;
	private final ChatMessageRepository chatMessageRepository;
	private final ChatSessionRepository chatSessionRepository;
	

	public ChatMessageResponse sendMessage(Long sessionId, Long userId, UUID guestId, ChatMessageSendRequest request) {
		
		long startTime = System.currentTimeMillis();
		
		 // -1) 세션 소유자 검증 (try-catch 밖에 둬서 403/404가 그대로 응답되게 함)
	    getOwnedSession(sessionId, userId, guestId);
		
		// 0) 이력을 먼저 조회 (현재 질문은 아직 DB에 없음)
	    String conversationHistory;
	    try {
	        conversationHistory = buildConversationHistory(sessionId);
	    } catch (Exception e) {
	        log.warn("대화 이력 조회 실패, 이력 없이 진행 - sessionId: {}", sessionId, e);
	        conversationHistory = "";
	    }
		
		// 1) 사용자 메시지 저장 + AI 답변 자리(PENDING 상태)를 먼저 DB에 만들어둠
		//    아직 LLM 응답은 안 왔지만, "생성중"이라는 행을 미리 확보하는 것
		ChatMessage assistantMessage = persistence.saveUserAndPendingAssistant(sessionId, request);
		Long assistantId = assistantMessage.getId();
		
		// 3) 실제 AI 작업은 비동기로 실행
		answerStreamer.startAsync(
		        sessionId,
		        assistantId,
		        request.content(),
		        conversationHistory   // 위에서 현재 질문 저장 전에 조회해 둔 이력
		);

		long latencyMs = System.currentTimeMillis() - startTime;
		
        
		// 커밋된 최신 상태를 트랜잭션 안에서 조회해 응답까지 만들어 반환
	    return persistence.getResponse(assistantId, latencyMs);
		
	}
	
//	private record ContextResult(String text, List<FaqReference> faqs) {
//	    static ContextResult empty() { return new ContextResult("", List.of()); }
//	}
	
	/**
	 * 같은 세션의 이전 대화 내용을 "USER: ~~\nASSISTANT: ~~" 형태의 한 문자열로 이어붙인다.
	 * LLM에게 이전 맥락을 알려주기 위한 용도.
	 */
	private String buildConversationHistory (Long sessionId) {
		
		List<ChatMessage> recent = new ArrayList<>(
		        chatMessageRepository.findBySession_IdAndStatusOrderByCreatedAtDescIdDesc(
		                sessionId,
		                ChatMessageStatus.COMPLETED,
		                PageRequest.of(0, MAX_HISTORY_TURNS * 2)));

	    Collections.reverse(recent);  // DESC로 가져왔으니 시간순(ASC)으로 복원

	    return recent.stream()
	            .map(m -> "%s: %s".formatted(m.getRole(), PromptEscaper.escape(m.getContent())))
	            .collect(Collectors.joining("\n"));
		
	}
	
	/** 세션 존재 여부와 소유자를 검증한다. 실패 시 BusinessException. */
	public ChatSession getOwnedSession(Long sessionId, Long userId, UUID guestId) {
	    ChatSession session = chatSessionRepository.findById(sessionId)
	            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 세션입니다."));

	    boolean isOwner = (session.getUserId() != null)
	            ? session.getUserId().equals(userId)
	            : session.getGuestId() != null && session.getGuestId().equals(guestId);

	    if (!isOwner) {
	        throw new BusinessException(ErrorCode.FORBIDDEN, "타인의 세션에는 접근할 수 없습니다.");
	    }
	    return session;
	}
	
	/**
	 * 특정 세션의 메시지 목록을 조회하는 API용 메소드.
	 * @Transactional(readOnly = true): 조회만 하는 트랜잭션이라고 명시 (DB 최적화 + 실수로 쓰기 방지)
	 */
	@Transactional(readOnly = true)
	public SessionMessagesResponse getMessages(Long sessionId, Long userId, UUID guestId) {
		
		getOwnedSession(sessionId, userId, guestId);
		
		
		List<ChatMessageItemResponse> messages = chatMessageRepository
				.findAllBySession_IdOrderByCreatedAtAsc(sessionId)
				.stream()
				.map(ChatMessageItemResponse::from)
				.toList();
		
		return new SessionMessagesResponse(sessionId, messages);
	}
}
