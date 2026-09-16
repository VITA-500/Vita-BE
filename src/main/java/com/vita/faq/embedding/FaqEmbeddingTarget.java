package com.vita.faq.embedding;

public record FaqEmbeddingTarget(Long id, String question, String answer) {

	public String toEmbeddingText() {
		return "질문: " + question + "\n답변: " + answer;
	}
}
