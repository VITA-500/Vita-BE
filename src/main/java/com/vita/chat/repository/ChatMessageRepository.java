package com.vita.chat.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.ChatSession;

public interface ChatMessageRepository  extends JpaRepository<ChatMessage, Long> {

	List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
	
	List<ChatMessage> findAllBySession_IdOrderByCreatedAtAsc(Long sessionId);
}
