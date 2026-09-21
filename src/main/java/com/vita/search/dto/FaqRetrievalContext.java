package com.vita.search.dto;

import java.util.List;

/**
 * BE3 → BE4 RAG Context 전달 계약. "관련 FAQ 없음"은 별도 플래그 없이
 * references가 비어있는 것으로 표현한다 — 플래그를 따로 두면 플래그와 목록이
 * 항상 일치해야 하는 불변조건이 생겨서, 그 자체를 없애는 쪽으로 BE4와 합의했다.
 *
 * @param references    threshold 이상인 참고 FAQ 목록 (유사도 내림차순). 없으면 빈 목록.
 * @param topSimilarity 검색된 후보 중 가장 높은 유사도(0~1). threshold 미달이라 references가
 *                      비어있어도 실제 최고 점수가 채워진다(BE4의 미해결 질문 자동 감지·검색 실패
 *                      분석용). 후보 자체가 없으면(ACTIVE 임베딩 FAQ가 0건) 0.0.
 */
public record FaqRetrievalContext(List<FaqReference> references, double topSimilarity) {

	/** 검색 후보가 아예 없을 때(임베딩된 ACTIVE FAQ 없음 등) 사용하는 빈 컨텍스트. */
	public static FaqRetrievalContext empty() {
		return new FaqRetrievalContext(List.of(), 0.0);
	}

	/** references가 비어있지 않으면 true. 별도 필드가 아니라 매번 계산되는 값이라 불일치가 날 수 없다. */
	public boolean hasRelevantFaq() {
		return !references.isEmpty();
	}
}
