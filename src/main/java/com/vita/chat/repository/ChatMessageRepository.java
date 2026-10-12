package com.vita.chat.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.vita.chat.ChatMessageStatus;
import com.vita.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.Query;

public interface ChatMessageRepository  extends JpaRepository<ChatMessage, Long> {
	
	List<ChatMessage> findAllBySession_IdOrderByCreatedAtAsc(Long sessionId);
	
	//  role 조건 없이 직전 메시지 5건만 조회
	List<ChatMessage> findTop5BySessionIdAndIdLessThanOrderByIdDesc(Long sessionId, Long id);
	
	List<ChatMessage> findBySession_IdAndStatusOrderByCreatedAtDescIdDesc(
            Long sessionId, ChatMessageStatus status, Pageable pageable);

	/** 미해결로 표시된 답변과, 그 답변 바로 앞의 사용자 질문 */
	interface UnresolvedMessageRow {
		Long getId();
		String getReason();
		LocalDateTime getCreatedAt();
		String getQuestion();
	}

	@Query(value = """
			SELECT a.id, a.unresolved_reason AS reason, a.created_at,
			       (SELECT u.content FROM chat_messages u
			         WHERE u.session_id = a.session_id AND u.role = 'USER' AND u.id < a.id
			         ORDER BY u.id DESC LIMIT 1) AS question
			FROM chat_messages a
			WHERE a.unresolved_reason IS NOT NULL
			""", nativeQuery = true)
	List<UnresolvedMessageRow> findUnresolvedWithQuestion();
}
