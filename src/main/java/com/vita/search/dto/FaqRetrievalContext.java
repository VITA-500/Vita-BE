package com.vita.search.dto;

import java.util.List;

/**
 * BE3 → BE4 RAG Context 전달 계약. "관련 FAQ 없음"은 별도 플래그 없이
 * references가 비어있는 것으로 표현한다 — 플래그를 따로 두면 플래그와 목록이
 * 항상 일치해야 하는 불변조건이 생겨서, 그 자체를 없애는 쪽으로 BE4와 합의했다.
 */
public record FaqRetrievalContext(List<FaqReference> references) {

	public static FaqRetrievalContext empty() {
		return new FaqRetrievalContext(List.of());
	}

	public boolean hasRelevantFaq() {
		return !references.isEmpty();
	}
}
