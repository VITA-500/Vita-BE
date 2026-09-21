package com.vita.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessageSendRequest(
//		@NotBlank(message = "질문 내용은 필수입니다.")
//		@Size(max = 2000, message = "질문은 2000자를 초과할 수 없습니다.")
		String content
		) {
}
