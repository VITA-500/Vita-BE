package com.vita.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.vita.chat.ChatMessageFeedback;
import com.vita.chat.ChatMessageRole;
import com.vita.chat.ChatMessageStatus;
import com.vita.chat.entity.ChatMessage;

public record ChatMessageItemResponse(
		Long messageId,
		ChatMessageRole role,
		String content, 
		LocalDateTime createdAt,
		ChatMessageStatus status,
		ChatStoreMap storeMap,
		List<ChatAction> actions,
		ChatMessageFeedback feedback
		) {
	public static ChatMessageItemResponse from(ChatMessage message) {
		return new ChatMessageItemResponse(
				message.getId(),
				message.getRole(),
				message.getContent(),
				message.getCreatedAt(),
				message.getStatus(),
				message.getStoreMap(),
				message.getActions(),
				message.getFeedback()
				);
		
	}
}
