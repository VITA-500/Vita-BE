package com.vita.chat.dto;

import com.vita.chat.ChatMessageFeedback;
import com.vita.chat.entity.ChatMessage;

public record ChatFeedbackResponse(
        Long messageId,
        ChatMessageFeedback feedback) {
    public static ChatFeedbackResponse from(ChatMessage message){
        return new ChatFeedbackResponse(message.getId(), message.getFeedback());
    }
}
