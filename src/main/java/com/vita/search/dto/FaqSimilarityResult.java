package com.vita.search.dto;

public record FaqSimilarityResult(
		Long id,
		String category,
		String subcategory,
		String question,
		String answer,
		double similarity) {
}
