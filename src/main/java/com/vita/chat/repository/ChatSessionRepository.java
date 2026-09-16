package com.vita.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vita.chat.entity.ChatSession;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

	
}
