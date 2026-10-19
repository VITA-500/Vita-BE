package com.vita.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.vita.chat.ChatMessageStatus;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.ChatMessageFaqRef;

public record ChatMessageResponse(
		Long messageId,
		ChatMessageStatus status,
		String answer,
		List<Long> relatedFaqIds,
		List<ChatPlanCard> plans,
		LocalDateTime createAt,
		long latencyMs
		) {
	public static ChatMessageResponse of(ChatMessage  message, long latencyMs) {
		return new ChatMessageResponse(
				message.getId(),
				message.getStatus(),
				message.getContent(),
				message.getFaqRefs().stream()
				.map(ChatMessageFaqRef :: getFaqId)
				.toList(),
				message.getPlans(),
				message.getCreatedAt(),
				latencyMs
				);
	}
}
