package com.vita.search.service;

import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.repository.PlanLookupRepository;
import com.vita.search.repository.PlanVectorSearchRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 요금제 검색 한 번을 담당한다. 벡터 유사도 검색에 더해, 질문에서 가격·데이터량·대상 그룹·무제한 여부를
 * 뽑아 plans 테이블 컬럼과 직접 비교하는 조건 매칭을 합친다.
 *
 * <p>조건이 추출되고 그 조건을 만족하는 요금제가 있으면, 그 요금제들만 유사도 순으로 돌려준다. 조건이
 * 없거나 만족하는 요금제가 없으면 기존처럼 벡터 유사도 상위 결과를 그대로 돌려준다(안전한 폴백).
 * FaqRetrievalServiceImpl(실서비스)과 회귀 테스트 러너가 같은 로직을 쓰도록 한곳에 모았다.
 */
@Service
public class PlanSearchService {

	/** 조건 매칭 결과를 유사도 순으로 정렬하기 위해 벡터 검색에서 넉넉히 가져오는 후보 수(요금제는 15종 규모). */
	private static final int CANDIDATE_POOL = 50;

	private final PlanVectorSearchRepository planVectorSearchRepository;
	private final PlanLookupRepository planLookupRepository;

	public PlanSearchService(PlanVectorSearchRepository planVectorSearchRepository,
			PlanLookupRepository planLookupRepository) {
		this.planVectorSearchRepository = planVectorSearchRepository;
		this.planLookupRepository = planLookupRepository;
	}

	/**
	 * 요금제 검색 결과.
	 *
	 * @param results          후보 목록(유사도 내림차순, 최대 topK). 유사도 threshold는 적용하기 전 상태다.
	 * @param conditionMatched 질문에서 추출한 조건으로 후보를 좁혔는지. true면 threshold 없이도 관련 요금제로 본다.
	 */
	public record PlanSearchOutcome(List<PlanSimilarityResult> results, boolean conditionMatched) {
	}

	/**
	 * @param query       사용자 질문 원문(조건 추출용)
	 * @param queryVector 같은 질문의 임베딩 벡터
	 * @param topK        최대 반환 개수
	 */
	public PlanSearchOutcome search(String query, float[] queryVector, int topK) {
		List<PlanSimilarityResult> pool = planVectorSearchRepository.searchBySimilarity(queryVector, 0.0, CANDIDATE_POOL);

		PlanQueryConditions conditions = PlanQueryConditionExtractor.extract(query);
		if (!conditions.isEmpty()) {
			Set<String> matched = findMatching(conditions);
			if (!matched.isEmpty()) {
				List<PlanSimilarityResult> narrowed = pool.stream()
						.filter(candidate -> matched.contains(candidate.planCode()))
						.limit(topK)
						.toList();
				return new PlanSearchOutcome(narrowed, true);
			}
		}

		return new PlanSearchOutcome(pool.stream().limit(topK).toList(), false);
	}

	/** 모든 조건을 만족하는 요금제를 찾고, 없으면 무제한 여부 조건만 빼고 한 번 더 찾는다. */
	private Set<String> findMatching(PlanQueryConditions conditions) {
		Set<String> matched = planLookupRepository.findPlanCodesByConditions(conditions);
		if (matched.isEmpty() && conditions.dataPolicy() != null) {
			PlanQueryConditions relaxed = conditions.withoutDataPolicy();
			if (!relaxed.isEmpty()) {
				matched = planLookupRepository.findPlanCodesByConditions(relaxed);
			}
		}
		return matched;
	}
}
