package com.vita.search.service;

import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.repository.PlanLookupRepository;
import com.vita.search.repository.PlanVectorSearchRepository;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 요금제 검색 한 번을 담당한다. 벡터 유사도 검색에 더해, 질문에서 가격·데이터량·대상 그룹·무제한 여부를
 * 뽑아 plans 테이블 컬럼과 직접 비교하는 조건 매칭을 합친다.
 *
 * <p>조건이 추출되고 그 조건을 만족하는 요금제가 있으면, 그 요금제들만 돌려준다. 조건이 없거나 만족하는
 * 요금제가 없으면 기존처럼 벡터 유사도 상위 결과를 그대로 돌려준다(안전한 폴백).
 *
 * <p>사용자가 워치·태블릿을 말하지 않았다면 기기 전용 요금제는 결과에서 뺀다. 스마트폰 사용자에게
 * "가장 싼 요금제"로 스마트워치 전용(11,000원)이 나오는 것을 막기 위해서다. 다만 그렇게 하면 결과가 하나도
 * 안 남는 경우("1만1천원짜리 요금제": 그 가격은 워치 요금제뿐)에는 뺀 것을 되돌려서 답을 준다.
 *
 * <p>검색 파이프라인(RetrievalPipeline, 실서비스)과 회귀 테스트 러너가 같은 로직을 쓰도록 한곳에 모았다.
 */
@Service
public class PlanSearchService {

	/** 조건 매칭 결과를 유사도 순으로 정렬하기 위해 벡터 검색에서 넉넉히 가져오는 후보 수(요금제는 15종 규모). */
	private static final int CANDIDATE_POOL = 50;

	/**
	 * 조건에 매칭된 요금제를 돌려줄 최대 개수. "3만원대", "5만원 이하"처럼 범위 질문은 조건을 만족하는 요금제가
	 * topK(보통 3)보다 많은데, 3개만 주면 LLM이 그것이 전부라고 답해서 사용자가 오해한다.
	 */
	private static final int MATCHED_RESULT_LIMIT = 10;

	/** 워치·태블릿처럼 특정 기기에서만 쓸 수 있는 요금제의 대상 그룹(plans.target_group). */
	private static final Set<String> DEVICE_ONLY_GROUPS = Set.of("WATCH", "TABLET");

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
	 * @param results          후보 목록. 벡터 검색이면 유사도 내림차순 최대 topK, 조건 매칭이면 최대
	 *                         {@value #MATCHED_RESULT_LIMIT}개(topK보다 많을 수 있다)이고 가격 조건이 있으면 월 요금
	 *                         오름차순, 없으면 유사도 내림차순이다. 유사도 threshold는 적용 전.
	 * @param conditionMatched 질문에서 추출한 조건으로 후보를 좁혔는지. true면 threshold 없이도 관련 요금제로 본다.
	 */
	public record PlanSearchOutcome(List<PlanSimilarityResult> results, boolean conditionMatched) {
	}

	/**
	 * @param query       사용자 질문 원문(조건 추출·기기 언급 판단용)
	 * @param queryVector 같은 질문의 임베딩 벡터
	 * @param topK        벡터 검색일 때의 최대 반환 개수
	 */
	public PlanSearchOutcome search(String query, float[] queryVector, int topK) {
		List<PlanSimilarityResult> pool = planVectorSearchRepository.searchBySimilarity(queryVector, 0.0, CANDIDATE_POOL);
		Set<String> deviceOnlyCodes = deviceOnlyCodesToExclude(query);

		PlanQueryConditions conditions = PlanQueryConditionExtractor.extract(query);
		if (!conditions.isEmpty()) {
			Set<String> matched = findMatching(conditions);
			if (!matched.isEmpty()) {
				Set<String> scoped = new HashSet<>(matched);
				scoped.removeAll(deviceOnlyCodes);
				// 기기 전용을 빼면 아무것도 안 남는 질문("1만1천원짜리")은 빼기 전 결과를 그대로 쓴다.
				Set<String> use = scoped.isEmpty() ? matched : scoped;

				// 가격 조건이 있는 질문("3만원대", "5만원 이하")은 싼 순으로 보여주는 게 자연스럽다. 그 외 질문
				// ("비타 유스 70 설명해줘", "무제한이면서 쉐어링 되는 요금제")은 질문과 가장 비슷한 요금제가 1등이어야 해서
				// 유사도 순을 유지한다(가격순으로 바꾸면 이름을 지목한 질문의 1등이 더 싼 요금제로 바뀐다).
				boolean hasFeeCondition = conditions.feeMin() != null || conditions.feeMax() != null;
				Comparator<PlanSimilarityResult> order = hasFeeCondition
						? Comparator.comparingInt(PlanSimilarityResult::monthlyFee)
								.thenComparing(Comparator.comparingDouble(PlanSimilarityResult::similarity).reversed())
						: Comparator.comparingDouble(PlanSimilarityResult::similarity).reversed();

				List<PlanSimilarityResult> narrowed = pool.stream()
						.filter(candidate -> use.contains(candidate.planCode()))
						.sorted(order)
						.limit(Math.max(topK, MATCHED_RESULT_LIMIT))
						.toList();
				return new PlanSearchOutcome(narrowed, true);
			}
		}

		List<PlanSimilarityResult> scopedPool = pool.stream()
				.filter(candidate -> !deviceOnlyCodes.contains(candidate.planCode()))
				.toList();
		List<PlanSimilarityResult> base = scopedPool.isEmpty() ? pool : scopedPool;
		return new PlanSearchOutcome(base.stream().limit(topK).toList(), false);
	}

	/** 질문이 워치·태블릿을 언급하지 않았으면 기기 전용 요금제의 plan_code 집합을, 언급했으면 빈 집합을 돌려준다. */
	private Set<String> deviceOnlyCodesToExclude(String query) {
		if (PlanQueryConditionExtractor.mentionsDevicePlan(query)) {
			return Set.of();
		}
		Map<String, String> groupByCode = planLookupRepository.findTargetGroupByPlanCode();
		return groupByCode.entrySet().stream()
				.filter(entry -> DEVICE_ONLY_GROUPS.contains(entry.getValue()))
				.map(Map.Entry::getKey)
				.collect(Collectors.toSet());
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
