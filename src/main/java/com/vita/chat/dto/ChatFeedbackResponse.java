package com.vita.chat.dto;

import com.vita.chat.ChatMessageFeedback;
import com.vita.chat.entity.ChatMessage;

/**
 * @param comment 저장된 (아쉬워요) 이유. (아쉬워요)가 아니거나 이유가 없으면 null
 */
public record ChatFeedbackResponse(
        Long messageId,
        ChatMessageFeedback feedback,
        String comment) {
    public static ChatFeedbackResponse from(ChatMessage message, String comment){
        return new ChatFeedbackResponse(message.getId(), message.getFeedback(), comment);
    }
}
