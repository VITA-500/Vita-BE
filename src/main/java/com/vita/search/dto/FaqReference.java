package com.vita.search.dto;

import java.time.LocalDateTime;

/** RAG 응답에 참고 자료로 포함되는 FAQ 한 건 — BE4/FE1에 전달되는 대외 계약. */
public record FaqReference(
		Long faqId,
		String category,
		String subcategory,
		String question,
		String answer,
		double similarity,
		LocalDateTime updatedAt) {
}
