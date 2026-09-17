package com.vita.chat.dto;

import java.util.List;

public record ChatSessionListResponse(
		List<ChatSessionSummaryResponse> sessions
		) {
}
