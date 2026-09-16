package com.vita.search.dto;

import java.util.List;

/**
 * BE3 → BE4 RAG Context 전달 계약. "관련 FAQ 없음"은 별도 플래그 없이
 * references가 비어있는 것으로 표현한다 — 플래그를 따로 두면 플래그와 목록이
 * 항상 일치해야 하는 불변조건이 생겨서, 그 자체를 없애는 쪽으로 BE4와 합의했다.
 */
public record FaqRetrievalContext(List<FaqReference> references) {

	/** 참고할 FAQ가 없을 때(threshold 미달 등) 사용하는 빈 컨텍스트. */
	public static FaqRetrievalContext empty() {
		return new FaqRetrievalContext(List.of());
	}

	/** references가 비어있지 않으면 true. 별도 필드가 아니라 매번 계산되는 값이라 불일치가 날 수 없다. */
	public boolean hasRelevantFaq() {
		return !references.isEmpty();
	}
}
