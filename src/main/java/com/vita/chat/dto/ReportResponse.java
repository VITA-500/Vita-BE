package com.vita.chat.dto;

import java.time.LocalDateTime;

import com.vita.chat.ChatMessageRole;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.UnresolvedQuestionReport;

public record ReportResponse(
		Long messageId,
		LocalDateTime reportedAt,
		String comment
		) {
	public static ReportResponse from(UnresolvedQuestionReport report) {
		return new ReportResponse(report.getMessage().getId(), report.getCreatedAt(), report.getComment());
	}
}