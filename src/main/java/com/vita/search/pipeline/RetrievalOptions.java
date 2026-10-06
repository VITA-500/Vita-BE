package com.vita.search.pipeline;

import com.vita.search.service.FaqCandidateSelector;

/**
 * 검색 한 번의 실행 옵션.
 *
 * @param topK         최종 결과로 돌려줄 최대 FAQ 개수
 * @param poolSize     후보 검색에서 가져올 후보 개수. 서비스는 topK에서 정해진 값({@link FaqCandidateSelector#poolSize})을 쓰고,
 *                     평가 러너는 풀 크기를 바꿔 가며 비교하려고 직접 지정한다.
 * @param includePlans 요금제 검색까지 할지. FAQ만 평가하는 러너는 false로 두어 요금제 검색 시간을 아낀다.
 * @param logDetails   검색 결과 요약 로그(관련 없음, 순위 신호, 단계별 시간)를 남길지. 평가 러너가 수백 번 호출할 때는 false로 둔다.
 */
public record RetrievalOptions(int topK, int poolSize, boolean includePlans, boolean logDetails) {

	/** 실제 서비스(BE4 호출)와 같은 옵션: 후보 풀은 topK에서 정해지고, FAQ와 요금제를 모두 검색하고, 로그를 남긴다. */
	public static RetrievalOptions forService(int topK) {
		return new RetrievalOptions(topK, FaqCandidateSelector.poolSize(topK), true, true);
	}

	/** 평가 러너용 옵션: 로그를 남기지 않는다. */
	public static RetrievalOptions forEval(int topK, int poolSize, boolean includePlans) {
		return new RetrievalOptions(topK, poolSize, includePlans, false);
	}
}
