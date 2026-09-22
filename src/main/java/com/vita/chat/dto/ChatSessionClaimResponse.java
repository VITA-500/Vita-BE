package com.vita.chat.dto;

import java.time.LocalDateTime;

import com.vita.chat.entity.ChatSession;

public record ChatSessionClaimResponse(
		Long sessionId,
		LocalDateTime claimedAt
		) {
	
	public static ChatSessionClaimResponse from(ChatSession session) {
		return new ChatSessionClaimResponse(
				session.getId(),
				session.getUpdatedAt()
				);
	}
}
