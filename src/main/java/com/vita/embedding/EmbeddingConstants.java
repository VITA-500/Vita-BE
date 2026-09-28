package com.vita.embedding;

public final class EmbeddingConstants {

	public static final String MODEL_NAME = "intfloat/multilingual-e5-base";
	// FAQ v2: "질문: {question}\n답변: {answer}" 전체를 임베딩한다.
	public static final String VERSION = "v2";
	public static final int DIMENSIONS = 768;

	private EmbeddingConstants() {
	}
}
