package com.vita.chat.dto;

import java.time.LocalDateTime;

import com.vita.chat.entity.ChatSession;

public record ChatSessionSummaryResponse(
		Long sessionId,
	    String title,
	    LocalDateTime updatedAt
	    ) {
	public static ChatSessionSummaryResponse from(ChatSession session) {
        return new ChatSessionSummaryResponse(
            session.getId(),
            session.getTitle(),
            session.getUpdatedAt()
        );
    }
}
