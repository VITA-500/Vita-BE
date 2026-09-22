package com.vita.search.dto;

import java.time.LocalDateTime;

/**
 * pgvector 유사도 검색(PlanVectorSearchRepository) 결과 한 건.
 *
 * @param similarity 코사인 유사도, 0~1 범위 (1에 가까울수록 유사)
 */
public record PlanSimilarityResult(
		Long id,
		String planCode,
		String name,
		String summary,
		int monthlyFee,
		String description,
		double similarity,
		LocalDateTime updatedAt) {
}
