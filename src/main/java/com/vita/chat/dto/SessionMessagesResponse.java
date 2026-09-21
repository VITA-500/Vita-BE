package com.vita.chat.dto;

import java.util.List;

import com.vita.chat.entity.ChatMessage;

public record SessionMessagesResponse(
		Long sessionId,
		List<MessageResponse> messages
		) {
	public static SessionMessagesResponse from(Long sessionId, List<ChatMessage> messages) {
		List<MessageResponse> messageResponse = messages.stream()
				.map(MessageResponse::from)
				.toList();
		
		return new SessionMessagesResponse(sessionId, messageResponse);
	}
}
