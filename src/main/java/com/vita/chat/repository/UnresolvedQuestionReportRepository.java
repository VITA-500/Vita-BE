package com.vita.chat.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vita.chat.entity.UnresolvedQuestionReport;

public interface UnresolvedQuestionReportRepository
extends JpaRepository<UnresolvedQuestionReport, Long> {

Optional<UnresolvedQuestionReport> findByMessageIdAndUserId(Long messageId, Long userId);
}