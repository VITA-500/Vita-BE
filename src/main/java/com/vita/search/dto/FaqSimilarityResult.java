package com.vita.search.dto;

import java.time.LocalDateTime;

/**
 * pgvector 유사도 검색(FaqVectorSearchRepository) 결과 한 건.
 *
 * @param similarity 코사인 유사도, 0~1 범위 (1에 가까울수록 유사)
 * @param updatedAt  FE1이 참고 FAQ 메타데이터로 표시할 마지막 수정 시각 (FaqReference로 그대로 전달됨)
 */
public record FaqSimilarityResult(
		Long id,
		String category,
		String subcategory,
		String question,
		String answer,
		double similarity,
		LocalDateTime updatedAt) {
}
