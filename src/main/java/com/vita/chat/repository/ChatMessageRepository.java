package com.vita.chat.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.ChatSession;

public interface ChatMessageRepository  extends JpaRepository<ChatMessage, Long> {
	
	List<ChatMessage> findAllBySession_IdOrderByCreatedAtAsc(Long sessionId);
	
	// ChatMessageRepository에 추가 — role 조건 없이 직전 메시지 5건만 조회
	List<ChatMessage> findTop5BySessionIdAndIdLessThanOrderByIdDesc(Long sessionId, Long id);
}
