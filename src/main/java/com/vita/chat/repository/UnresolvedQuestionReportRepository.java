package com.vita.chat.repository;

import java.util.List;
import java.util.Optional;

import com.vita.chat.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import com.vita.chat.entity.UnresolvedQuestionReport;

public interface UnresolvedQuestionReportRepository
extends JpaRepository<UnresolvedQuestionReport, Long> {

Optional<UnresolvedQuestionReport> findByMessageIdAndUserId(Long messageId, Long userId);

List<UnresolvedQuestionReport> findAllByStatus(ReportStatus status);
}