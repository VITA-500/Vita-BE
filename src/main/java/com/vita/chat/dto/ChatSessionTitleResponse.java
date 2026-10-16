package com.vita.chat.dto;

import com.vita.chat.entity.ChatSession;

public record ChatSessionTitleResponse(
        Long sessionId,
        String title) {
    public static ChatSessionTitleResponse from(ChatSession session) {
        return new ChatSessionTitleResponse(session.getId(), session.getTitle());
    }
}
