package com.vita.chat.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.vita.chat.ReportStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vita.chat.entity.UnresolvedQuestionReport;

public interface UnresolvedQuestionReportRepository
        extends JpaRepository<UnresolvedQuestionReport, Long> {

    Optional<UnresolvedQuestionReport> findByMessageIdAndUserId(Long messageId, Long userId);

    /** 신고된 답변 내용도 쓰므로 답변 메시지를 함께 조회한다 (신고마다 따로 조회하면 N+1) */
    @EntityGraph(attributePaths = "message")
    List<UnresolvedQuestionReport> findAllByStatus(ReportStatus status);

    /**
     * (아쉬워요) 저장. 같은 (답변, 회원) 줄이 이미 있으면 아무것도 하지 않는다
     * 연속 클릭으로 두 요청이 동시에 와도 중복 오류(500) 없이 한 줄만 남는다
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO unresolved_question_reports (message_id, user_id, question_text, status, created_at)
            VALUES (:messageId, :userId, :questionText, 'OPEN', :createdAt)
            ON CONFLICT (message_id, user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("messageId") Long messageId, @Param("userId") Long userId,
                       @Param("questionText") String questionText, @Param("createdAt") LocalDateTime createdAt);

    void deleteByMessageIdAndUserIdAndStatus(Long messageId, Long userId, ReportStatus status);

    List<UnresolvedQuestionReport> findAllByMessageIdInAndStatus(Collection<Long> messageIds, ReportStatus status);
}