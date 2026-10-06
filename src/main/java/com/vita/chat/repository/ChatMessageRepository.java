package com.vita.chat.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.vita.chat.ChatMessageStatus;
import com.vita.chat.entity.ChatMessage;

public interface ChatMessageRepository  extends JpaRepository<ChatMessage, Long> {
	
	List<ChatMessage> findAllBySession_IdOrderByCreatedAtAsc(Long sessionId);
	
	//  role 조건 없이 직전 메시지 5건만 조회
	List<ChatMessage> findTop5BySessionIdAndIdLessThanOrderByIdDesc(Long sessionId, Long id);
	
	List<ChatMessage> findBySession_IdAndStatusOrderByCreatedAtDescIdDesc(
            Long sessionId, ChatMessageStatus status, Pageable pageable);
}
