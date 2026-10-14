package com.vita.search.eval;

/**
 * 요금제 평가에서 한 질문이 어떤 경로로 요금제 목록을 만들었는지.
 *
 * <p>요금제 검색은 질문에서 가격·데이터량·대상 같은 조건을 읽어 요금제를 좁히는 조건 매칭이 먼저고, 조건이 없거나 맞는 요금제가
 * 없을 때만 벡터 유사도 상위를 돌려준다. 최상급("제일 싼 요금제") 질문은 검색을 거치지 않고 정형 조회로 답한다. 후보를 가져오는
 * 방식(Hybrid 등)을 바꾸면 순서가 실제로 쓰이는 벡터 경로에서만 결과가 달라지므로, 점수를 경로별로 나눠 봐야 그 효과가 보인다.
 */
enum PlanEvalPath {

	/** 질문에서 읽은 조건으로 요금제를 좁힌 경우. 결과는 조건을 만족하는 요금제이고 순서는 가격순 또는 유사도순이다. */
	CONDITION_MATCHED("조건 매칭 경로"),
	/** 조건이 없거나 맞는 요금제가 없어 벡터 유사도 상위를 돌려준 경우. 후보 검색 방식의 순서가 그대로 쓰인다. */
	VECTOR("벡터 경로"),
	/** 최상급 질문. 질문 변환·검색 방식과 무관하게 정형 조회로 답한다. */
	LOOKUP("정형 조회(최상급) 경로");

	/** 평가 러너가 검색 경로 질문에 붙이는 경로 이름. */
	static final String SEARCH = "search";
	/** 평가 러너가 최상급 질문에 붙이는 경로 이름. */
	static final String LOOKUP_NAME = "lookup";

	private final String label;

	PlanEvalPath(String label) {
		this.label = label;
	}

	/** 로그에 쓰는 이름. */
	String label() {
		return label;
	}

	/**
	 * 질문의 경로 이름과 조건 매칭 여부로 경로를 정한다. 정형 조회는 조건 매칭 여부와 상관없이 정형 조회 경로다.
	 *
	 * @param path             평가 러너가 붙인 경로 이름({@value #SEARCH} 또는 {@value #LOOKUP_NAME})
	 * @param conditionMatched 검색 경로에서 조건으로 요금제를 좁혔는지
	 * @throws IllegalArgumentException 알 수 없는 경로 이름
	 */
	static PlanEvalPath of(String path, boolean conditionMatched) {
		if (LOOKUP_NAME.equals(path)) {
			return LOOKUP;
		}
		if (SEARCH.equals(path)) {
			return conditionMatched ? CONDITION_MATCHED : VECTOR;
		}
		throw new IllegalArgumentException("알 수 없는 요금제 평가 경로: " + path);
	}
}
