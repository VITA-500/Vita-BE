package com.vita.chat.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ChatMessageSendRequest(
		// 빈 질문, 너무 긴 질문은 LLM까지 가지 않게 막는다 (질문마다 LLM 호출 비용)
		@NotBlank(message = "질문 내용은 필수입니다.")
		@Size(max = 2000, message = "질문은 2000자를 초과할 수 없습니다.")
		String content,

		// 사용자 위치 (선택). 매장 질문에서 가까운 매장을 찾을 때 사용
		@DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
		@DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lng
		) {
}
