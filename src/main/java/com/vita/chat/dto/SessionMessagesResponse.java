package com.vita.chat.dto;

import java.util.List;

import com.vita.chat.entity.ChatMessage;

public record SessionMessagesResponse(
		Long sessionId,
		List<ChatMessageItemResponse> messages
		) {
	public static SessionMessagesResponse from(Long sessionId, List<ChatMessage> messages) {
		List<ChatMessageItemResponse> messageResponse = messages.stream()
				.map(ChatMessageItemResponse::from)
				.toList();
		
		return new SessionMessagesResponse(sessionId, messageResponse);
	}
}
