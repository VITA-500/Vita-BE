package com.vita.search.dto;

import java.util.List;

/**
 * BE3 → BE4 RAG Context 전달 계약. "관련 없음"은 별도 플래그 없이 references/planReferences가
 * 비어있는 것으로 표현한다 — 플래그를 따로 두면 플래그와 목록이 항상 일치해야 하는 불변조건이
 * 생겨서, 그 자체를 없애는 쪽으로 BE4와 합의했다.
 *
 * @param references     FAQ threshold 이상인 참고 FAQ 목록 (유사도 내림차순). 없으면 빈 목록.
 * @param planReferences 요금제 threshold 이상인 참고 요금제 목록 (유사도 내림차순). 없으면 빈 목록.
 *                       FAQ와 별도 목록으로 둬서 기존 references 소비 코드는 영향받지 않는다.
 * @param topSimilarity  FAQ·요금제 후보를 통틀어 가장 높은 유사도(0~1). threshold 미달이라
 *                       참고 목록이 비어있어도 실제 최고 점수가 채워진다(BE4의 미해결 질문
 *                       자동 감지·검색 실패 분석용). 후보 자체가 없으면 0.0.
 * @param planNotice     요금제 전체 목록을 물은 질문에서 LLM에게 줄 안내 한 줄(전체 목록이라는 점, 기기 전용 요금제가 빠졌다는
 *                       점). 전체 목록 요청이 아니면 null. 기존 소비 코드는 이 값을 몰라도 영향받지 않는다.
 */
public record FaqRetrievalContext(List<FaqReference> references, List<PlanReference> planReferences, double topSimilarity,
		String planNotice) {

	/** 요금제 안내 문구가 없는 일반 컨텍스트. 기존 3개 인자 호출을 그대로 쓸 수 있게 둔다. */
	public FaqRetrievalContext(List<FaqReference> references, List<PlanReference> planReferences, double topSimilarity) {
		this(references, planReferences, topSimilarity, null);
	}

	/** 검색 후보가 아예 없을 때(임베딩된 ACTIVE 데이터 없음 등) 사용하는 빈 컨텍스트. */
	public static FaqRetrievalContext empty() {
		return new FaqRetrievalContext(List.of(), List.of(), 0.0);
	}

	/** references가 비어있지 않으면 true. */
	public boolean hasRelevantFaq() {
		return !references.isEmpty();
	}

	/** planReferences가 비어있지 않으면 true. */
	public boolean hasRelevantPlan() {
		return !planReferences.isEmpty();
	}

	/** FAQ든 요금제든 하나라도 참고할 게 있으면 true. */
	public boolean hasAnyRelevant() {
		return hasRelevantFaq() || hasRelevantPlan();
	}
}
