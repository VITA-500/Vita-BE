package com.vita.embedding;

/**
 * 검색 질문과 FAQ 문서를 동일한 임베딩 공간으로 변환하는 공급자 계약.
 * 구현체는 모델이 요구하는 query/document prefix 처리까지 책임진다.
 */
public interface EmbeddingProvider {

	float[] embedQuery(String text);

	float[] embedDocument(String text);
}
