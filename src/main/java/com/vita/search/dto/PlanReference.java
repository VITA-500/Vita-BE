package com.vita.search.dto;

import java.time.LocalDateTime;

/** RAG 응답에 참고 자료로 포함되는 요금제 한 건 — BE4/FE1에 전달되는 대외 계약. */
public record PlanReference(
		Long planId,
		String planCode,
		String name,
		String summary,
		int monthlyFee,
		String description,
		double similarity,
		LocalDateTime updatedAt) {
}
