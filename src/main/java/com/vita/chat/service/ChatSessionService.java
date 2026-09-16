package com.vita.chat.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.vita.chat.dto.ChatSessionCreateResponse;
import com.vita.chat.entity.ChatSession;
import com.vita.chat.repository.ChatSessionRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

	private final ChatSessionRepository chatSessionRepository;
	
	@Transactional
	public ChatSessionCreateResponse createSession(Long userId) {
		ChatSession session = ChatSession.builder()
				.userId(userId)
				.title(null)
				.build();
		
		ChatSession saved = chatSessionRepository.save(session);
		
		return ChatSessionCreateResponse.from(saved);
	}
	
}
