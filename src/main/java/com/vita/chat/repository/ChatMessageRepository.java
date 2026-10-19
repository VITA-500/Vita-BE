package com.vita.chat.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.vita.chat.ChatMessageStatus;
import com.vita.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository  extends JpaRepository<ChatMessage, Long> {

	List<ChatMessage> findAllBySession_IdOrderByCreatedAtAsc(Long sessionId);

	//  role 조건 없이 직전 메시지 5건만 조회
	List<ChatMessage> findTop5BySessionIdAndIdLessThanOrderByIdDesc(Long sessionId, Long id);

	List<ChatMessage> findBySession_IdAndStatusOrderByCreatedAtDescIdDesc(
            Long sessionId, ChatMessageStatus status, Pageable pageable);

	/** 미해결로 표시된 답변(관리자가 무시한 것 제외)과, 그 답변 바로 앞의 사용자 질문 */
	interface UnresolvedMessageRow {
		Long getId();
		String getReason();
		LocalDateTime getCreatedAt();
		String getQuestion();
		String getAnswer();
	}

	@Query(value = """
			SELECT a.id, a.unresolved_reason AS reason, a.created_at, a.content AS answer,
			       (SELECT u.content FROM chat_messages u
			         WHERE u.session_id = a.session_id AND u.role = 'USER' AND u.id < a.id
			         ORDER BY u.id DESC LIMIT 1) AS question
			FROM chat_messages a
			WHERE a.unresolved_reason IS NOT NULL AND a.unresolved_dismissed_at IS NULL
			""", nativeQuery = true)
	List<UnresolvedMessageRow> findUnresolvedWithQuestion();

	/** (좋아요)/(아쉬워요)를 받은 답변의 피드백과 그 답변 바로 앞의 사용자 질문 */
	interface FeedbackRow {
		String getFeedback();
		String getQuestion();
	}

	@Query(value = """
			SELECT a.feedback::text AS feedback,
			       (SELECT u.content FROM chat_messages u
			         WHERE u.session_id = a.session_id AND u.role = 'USER' AND u.id < a.id
			         ORDER BY u.id DESC LIMIT 1) AS question
			FROM chat_messages a
			WHERE a.feedback IS NOT NULL
			""", nativeQuery = true)
	List<FeedbackRow> findFeedbackWithQuestion();

	/** 기간 안에 누른 피드백 수 (LIKE, DISLIKE별) */
	interface FeedbackCountRow {
		String getFeedback();
		long getCount();
	}

	@Query(value = """
			SELECT a.feedback::text AS feedback, COUNT(*) AS count
			FROM chat_messages a
			WHERE a.feedback_at >= :from AND a.feedback_at < :to
			GROUP BY a.feedback
			""", nativeQuery = true)
	List<FeedbackCountRow> countFeedbackBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
