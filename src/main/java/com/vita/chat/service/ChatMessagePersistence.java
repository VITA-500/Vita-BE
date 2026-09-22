package com.vita.chat.service;

import org.springframework.stereotype.Component;

import com.vita.chat.ChatMessageRole;
import com.vita.chat.ChatMessageStatus;
import com.vita.chat.dto.ChatMessageSendRequest;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.ChatSession;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.ChatSessionRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class ChatMessagePersistence {
	
	private final ChatMessageRepository chatMessageRepository;
	private final ChatSessionRepository chatSessionRepository;
	
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
    public void markCompleted(Long messageId, String answer) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
        message.markCompleted(answer);
        message.getSession().update();
    }

    @Transactional
    public void markFailed(Long messageId) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
        message.markFailed();
    }

}
