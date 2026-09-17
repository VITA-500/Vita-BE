package com.vita.chat.dto;

import java.time.LocalDateTime;

import com.vita.chat.entity.ChatSession;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatSessionCreateResponse {
	private Long sessionId;
	private LocalDateTime createdAt;
	
	public static ChatSessionCreateResponse from(ChatSession session) {
		return ChatSessionCreateResponse.builder()
				.sessionId(session.getId())
				.createdAt(session.getCreatedAt())
				.build();
	}
}
