package com.vita.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ChatMessageSendRequest(
//		@NotBlank(message = "질문 내용은 필수입니다.")
//		@Size(max = 2000, message = "질문은 2000자를 초과할 수 없습니다.")
		String content,

		// 사용자 위치 (선택). 매장 질문에서 가까운 매장을 찾을 때 사용
		BigDecimal lat,
		BigDecimal lng
		) {
}
