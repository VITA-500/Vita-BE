package com.vita.embedding;

/**
 * 검색 질문과 FAQ 문서를 동일한 임베딩 공간으로 변환하는 공급자 계약.
 * 구현체는 모델이 요구하는 query/document prefix 처리까지 책임진다.
 */
public interface EmbeddingProvider {

	/** 사용자 질문 원문을 받아 query prefix가 적용된 벡터를 반환한다. */
	float[] embedQuery(String text);

	/** 문서 원문을 받아 passage prefix가 적용된 벡터를 반환한다. */
	float[] embedDocument(String text);
}
