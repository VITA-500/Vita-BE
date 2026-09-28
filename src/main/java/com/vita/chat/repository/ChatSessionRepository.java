package com.vita.chat.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vita.chat.entity.ChatSession;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

	List<ChatSession> findAllByUserIdOrderByUpdatedAtDesc(Long userId);
}
