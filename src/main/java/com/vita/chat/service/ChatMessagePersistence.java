package com.vita.chat.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.vita.chat.ChatMessageRole;
import com.vita.chat.ChatMessageStatus;
import com.vita.chat.dto.ChatMessageResponse;
import com.vita.chat.dto.ChatMessageSendRequest;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.ChatMessageFaqRef;
import com.vita.chat.entity.ChatSession;
import com.vita.chat.repository.ChatMessageFaqRefRepository;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.ChatSessionRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class ChatMessagePersistence {
	
	private final ChatMessageRepository chatMessageRepository;
	private final ChatSessionRepository chatSessionRepository;
	private final ChatMessageFaqRefRepository chatMessageFaqRefRepository; 
	
	@Transactional
	public ChatMessage saveUserAndPendingAssistant(Long sessionId, ChatMessageSendRequest request) {
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
		
		
		// 3) 어시스턴트 메시지(PENDING)로 먼저 저장
		ChatMessage assistantMessage = ChatMessage.builder()
				.session(session)
				.role(ChatMessageRole.ASSISTANT)
				.content(null)
				.status(ChatMessageStatus.PENDING)
				.build();
		ChatMessage chatMessage = chatMessageRepository.save(assistantMessage);
		
		return chatMessage;
	}
	
	@Transactional
    public void markCompleted(Long messageId, String answer, List<Long> faqIds) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
        message.markCompleted(answer);
        message.getSession().update();
        
        List<ChatMessageFaqRef> refs = faqIds.stream()
                .distinct()
                .map(faqId -> ChatMessageFaqRef.of(message, faqId))
                .toList();
        chatMessageFaqRefRepository.saveAll(refs);
    }

    @Transactional
    public void markFailed(Long messageId, String errorMessage) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
        message.markFailed(errorMessage);
    }
    
    @Transactional(readOnly = true)
    public ChatMessageResponse getResponse(Long assistantId, long latencyMs) {
        ChatMessage message = chatMessageRepository.findById(assistantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 메시지입니다."));
        return ChatMessageResponse.of(message, latencyMs);   // faqRefs 등 지연 로딩도 여기서 읽힘
    }

}
