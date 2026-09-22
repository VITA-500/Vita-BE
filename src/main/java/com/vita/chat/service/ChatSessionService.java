package com.vita.chat.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.coyote.BadRequestException;
import org.springframework.stereotype.Service;

import com.vita.chat.dto.ChatSessionClaimResponse;
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
	
	@Transactional
	public ChatSessionClaimResponse claimSession(Long sessionId, Long userId, UUID guestId) {
		
		if(guestId == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "X-Guest-Id 헤더가 필요합니다");
		}
		
		ChatSession session = chatSessionRepository.findById(sessionId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		
		if(session.getUserId() != null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "이미 회원 계정에 연결된 세션입니다");
		}
		
		if (session.getGuestId() == null || !session.getGuestId().equals(guestId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN); // guest_id 불일치 = 타인 세션 탈취 시도
		}
		
		session.claimBy(userId); // userId 세팅 + guestId null 처리
		
		return ChatSessionClaimResponse.from(session);
		
	}
	
}
