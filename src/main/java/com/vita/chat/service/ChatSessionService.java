package com.vita.chat.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.coyote.BadRequestException;
import org.springframework.stereotype.Service;

import com.vita.chat.dto.ChatSessionCreateResponse;
import com.vita.chat.dto.ChatSessionListResponse;
import com.vita.chat.dto.ChatSessionSummaryResponse;
import com.vita.chat.entity.ChatSession;
import com.vita.chat.repository.ChatSessionRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

	private final ChatSessionRepository chatSessionRepository;
	
	/**
	 * 새 세션 생성
	 * @param userId
	 * @return
	 */
	@Transactional
	public ChatSessionCreateResponse createSession(Long userId, UUID guestId) {
		
		if (userId == null && guestId == null) {
			throw new 	BusinessException(ErrorCode.USER_NOT_FOUND, "사용자를 찾을 수 없습니다.");
		}
		
		ChatSession session = ChatSession.builder()
				.userId(userId)
				.guestId(guestId)
				.title(null)
				.build();
		
		ChatSession saved = chatSessionRepository.save(session);
		
		return ChatSessionCreateResponse.from(saved);
	}
	
	public ChatSessionListResponse getSessions(Long userId) {
		List<ChatSessionSummaryResponse> sessions = chatSessionRepository
				.findAllByUserIdOrderByUpdatedAtDesc(userId)
				.stream()
				.map(ChatSessionSummaryResponse::from)
				.toList();
		
		return new ChatSessionListResponse(sessions);
	}
	
}
