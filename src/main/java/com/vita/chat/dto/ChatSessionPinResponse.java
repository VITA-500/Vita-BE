package com.vita.chat.dto;

import com.vita.chat.entity.ChatSession;

public record ChatSessionPinResponse(
        Long sessionId,
        boolean pinned) {
    public static ChatSessionPinResponse from(ChatSession session) {
        return new ChatSessionPinResponse(session.getId(), session.isPinned());
    }
}