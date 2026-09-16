package com.vita.search.dto;

/**
 * pgvector 유사도 검색(FaqVectorSearchRepository) 결과 한 건.
 *
 * @param similarity 코사인 유사도, 0~1 범위 (1에 가까울수록 유사)
 */
public record FaqSimilarityResult(
		Long id,
		String category,
		String subcategory,
		String question,
		String answer,
		double similarity) {
}
