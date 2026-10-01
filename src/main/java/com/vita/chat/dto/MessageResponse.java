package com.vita.chat.dto;

import java.time.LocalDateTime;

import com.vita.chat.ChatMessageRole;
import com.vita.chat.ChatMessageStatus;
import com.vita.chat.entity.ChatMessage;

public record MessageResponse(
		Long messageId,
		ChatMessageRole role,
		String content, 
		LocalDateTime createdAt,
		ChatMessageStatus status
		) {
	public static MessageResponse from(ChatMessage message) {
		return new MessageResponse(
				message.getId(),
				message.getRole(),
				message.getContent(),
				message.getCreatedAt(),
				message.getStatus()
				);
		
	}
}
