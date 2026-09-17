package com.vita.search.service;

import com.vita.search.dto.FaqRetrievalContext;

/**
 * BE4(Chat/LLM)가 호출하는 RAG 검색 진입점. BE4는 이 인터페이스만 알면 되고,
 * 내부 구현(임베딩 모델, Top-K, threshold 등)이 바뀌어도 영향받지 않는다.
 */
public interface FaqRetrievalService {

	/**
	 * @param query 사용자 질문 원문 (임베딩 변환은 구현체 내부 책임)
	 * @param topK  최대 반환 개수 (권장값 3~5, BE4 프롬프트 예산에 맞춰 조정 가능)
	 * @return 참고 FAQ 목록을 담은 컨텍스트. 관련 FAQ가 없으면 references가 빈 컨텍스트.
	 */
	FaqRetrievalContext search(String query, int topK);
}
