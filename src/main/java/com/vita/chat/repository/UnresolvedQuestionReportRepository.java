package com.vita.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.vita.chat.ReportStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.vita.chat.entity.UnresolvedQuestionReport;

public interface UnresolvedQuestionReportRepository
        extends JpaRepository<UnresolvedQuestionReport, Long> {

    Optional<UnresolvedQuestionReport> findByMessageIdAndUserId(Long messageId, Long userId);

    /** 신고된 답변 내용도 쓰므로 답변 메시지를 함께 조회한다 (신고마다 따로 조회하면 N+1) */
    @EntityGraph(attributePaths = "message")
    List<UnresolvedQuestionReport> findAllByStatus(ReportStatus status);

    void deleteByMessageIdAndUserIdAndStatus(Long messageId, Long userId, ReportStatus status);

    List<UnresolvedQuestionReport> findAllByMessageIdInAndStatus(Collection<Long> messageIds, ReportStatus status);
}