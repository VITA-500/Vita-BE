package com.vita.search.service;

import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.pipeline.PlanRetriever;
import com.vita.search.pipeline.RetrievalQuery;
import com.vita.search.repository.PlanLookupRepository;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 *
 * <p>후보를 가져오는 방식(기본은 벡터 유사도, 평가·실험에서는 Hybrid 등)은 {@link PlanRetriever}로 갈아끼운다.
 * 어떤 구현을 쓸지는 {@code RetrievalPipelineConfig}가 설정({@code search.pipeline.plan-retriever})으로 골라 빈으로 만든다.
 */
public class PlanSearchService {

	/** 조건 매칭 결과를 유사도 순으로 정렬하기 위해 벡터 검색에서 넉넉히 가져오는 후보 수(요금제는 20종 규모). */
	private static final int CANDIDATE_POOL = 50;

	/**
	 * 조건에 매칭된 요금제를 돌려줄 최대 개수의 기본값. "3만원대", "5만원 이하"처럼 범위 질문은 조건을 만족하는 요금제가
	 * topK(보통 3)보다 많은데, 3개만 주면 LLM이 그것이 전부라고 답해서 사용자가 오해한다. 요금제가 20종 규모라
	 * 기본값은 전부를 줄 수 있는 20이다({@code plan.retrieval.matched-limit}으로 바꾼다).
	 */
	public static final int DEFAULT_MATCHED_RESULT_LIMIT = 20;

	/** 워치·태블릿처럼 특정 기기에서만 쓸 수 있는 요금제의 대상 그룹(plans.target_group). */
	private static final Set<String> DEVICE_ONLY_GROUPS = Set.of("WATCH", "TABLET");

	private final PlanRetriever planRetriever;
	private final PlanLookupRepository planLookupRepository;

	public PlanSearchService(PlanRetriever planRetriever, PlanLookupRepository planLookupRepository) {
		this.planRetriever = planRetriever;
		this.planLookupRepository = planLookupRepository;
	}

	/**
	 * 후보 검색기만 바꾼 같은 설정의 요금제 검색을 만든다. 평가 러너가 실험 변형(Hybrid 등)을 이 방법으로 갈아끼운다.
	 * 서비스 빈은 바뀌지 않는다.
	 */
	public PlanSearchService with(PlanRetriever retriever) {
		return new PlanSearchService(retriever, planLookupRepository);
	}

	/**
	 * 요금제 검색 결과.
	 *
	 * @param results          후보 목록. 벡터 검색이면 유사도 내림차순 최대 topK, 조건 매칭이면 최대
	 *                         matchedLimit개(topK보다 많을 수 있다)이고 가격 조건이 있으면 월 요금
	 *                         오름차순, 없으면 유사도 내림차순이다. 유사도 threshold는 적용 전.
	 * @param conditionMatched 질문에서 추출한 조건으로 후보를 좁혔는지. true면 threshold 없이도 관련 요금제로 본다.
	 *                         전체 목록 요청("전체 요금제 알려줘")도 안내할 요금제를 확정한 결과라 true다.
	 * @param listNotice       전체 목록 요청일 때 LLM에게 줄 안내 한 줄(전체 목록이라는 점, 기기 전용 요금제가 빠졌다는 점).
	 *                         전체 목록 요청이 아니면 null.
	 */
	public record PlanSearchOutcome(List<PlanSimilarityResult> results, boolean conditionMatched, String listNotice) {

		/** 전체 목록 요청이 아닌 일반 결과. */
		public PlanSearchOutcome(List<PlanSimilarityResult> results, boolean conditionMatched) {
			this(results, conditionMatched, null);
		}
	}

	/**
	 * 질문 하나로 조건 추출과 임베딩을 모두 하는 기존 호출(변환 없는 경로, 옛 회귀 러너). 요금제 개수 상한은 기본값을 쓴다.
	 *
	 * @param query       사용자 질문 원문(조건 추출·기기 언급 판단용)
	 * @param queryVector 같은 질문의 임베딩 벡터
	 * @param topK        벡터 검색일 때의 최대 반환 개수
	 */
	public PlanSearchOutcome search(String query, float[] queryVector, int topK) {
		return search(query, query, queryVector, topK, DEFAULT_MATCHED_RESULT_LIMIT);
	}

	/**
	 * 원문과 요금제용 질문(질문 변환 결과)을 따로 받는 검색. 임베딩 벡터는 요금제용 질문에서 만들고, 가격·데이터량·대상 같은
	 * 조건은 <b>원문과 요금제용 질문 양쪽에서 읽어 합친다</b>(같은 항목은 원문 우선).
	 *
	 * <p>양쪽에서 읽는 이유: 요금제용 질문이 "금액: 3만원대" 같은 라벨 형태이거나 "요금제" 단어가 없으면 조건 추출기가 반응하지
	 * 않을 수 있고, 반대로 원문이 "아까 그 요금제 5만원 이하로"처럼 지시어를 쓰면 질문 변환이 풀어 쓴 요금제용 질문에만 정보가
	 * 있기 때문이다. 워치·태블릿 언급 판단도 둘 중 하나에 있으면 인정한다.
	 *
	 * @param originalQuery 사용자 질문 원문
	 * @param planQuery     요금제 검색용 질문(변환이 없으면 원문과 같다)
	 * @param queryVector   planQuery의 임베딩 벡터
	 * @param topK          조건 매칭이 안 될 때(벡터 유사도 결과) 돌려줄 최대 요금제 개수
	 * @param matchedLimit  조건에 매칭된 요금제를 돌려줄 최대 개수
	 */
	public PlanSearchOutcome search(String originalQuery, String planQuery, float[] queryVector, int topK, int matchedLimit) {
		List<PlanSimilarityResult> pool = planRetriever.retrieve(new RetrievalQuery(planQuery, queryVector), CANDIDATE_POOL);
		PlanQueryConditions conditions = PlanQueryConditionExtractor.extract(originalQuery)
				.orElse(PlanQueryConditionExtractor.extract(planQuery));
		Set<String> deviceOnlyCodes = deviceOnlyCodesToExclude(originalQuery, planQuery);
		Set<String> excludedCodes = excludedGroupCodes(conditions);

		// "전체 요금제 알려줘"처럼 가격·데이터량 같은 조건 없이 전체 목록을 묻는 질문은, 벡터 상위 몇 개가 아니라 안내 가능한
		// 요금제 전체를 돌려준다. 조건이 있는 질문("3만원대 요금제 전부")은 아래 조건 매칭이 처리한다.
		// 워치·태블릿을 말했을 뿐인 질문("워치 요금제 포함해서 전체 요금제 알려줘")은 대상 그룹 조건 하나만 추출되는데, 이때도 전체
		// 목록을 원하므로 기기 전용을 포함한 전체를 돌려준다(그 그룹만 돌려주면 나머지가 빠진다).
		boolean onlyDeviceTarget = conditions.hasOnlyTargetGroup() && DEVICE_ONLY_GROUPS.contains(conditions.targetGroup());
		if ((conditions.isEmpty() || onlyDeviceTarget)
				&& (PlanListRequestDetector.matches(originalQuery) || PlanListRequestDetector.matches(planQuery))) {
			PlanSearchOutcome listed = listAllPlans(pool, deviceOnlyCodes, Math.max(topK, matchedLimit));
			if (!listed.results().isEmpty()) {
				return listed;
			}
		}

		if (!conditions.isEmpty()) {
			// "시니어 말고 3만원대"처럼 질문이 제외한 그룹의 요금제는 어떤 경우에도 결과에서 뺀다.
			Set<String> matched = new HashSet<>(findMatching(conditions));
			matched.removeAll(excludedCodes);
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
						.limit(Math.max(topK, matchedLimit))
						.toList();
				return new PlanSearchOutcome(narrowed, true);
			}
		}

		// 조건으로 좁히지 못한 질문은 벡터 유사도 상위를 돌려주되, 제외한 그룹은 여기서도 뺀다.
		List<PlanSimilarityResult> allowedPool = pool.stream()
				.filter(candidate -> !excludedCodes.contains(candidate.planCode()))
				.toList();
		if (allowedPool.isEmpty()) {
			allowedPool = pool;
		}
		List<PlanSimilarityResult> scopedPool = allowedPool.stream()
				.filter(candidate -> !deviceOnlyCodes.contains(candidate.planCode()))
				.toList();
		List<PlanSimilarityResult> base = scopedPool.isEmpty() ? allowedPool : scopedPool;
		return new PlanSearchOutcome(base.stream().limit(topK).toList(), false);
	}

	/**
	 * 전체 목록 요청의 결과를 만든다. 후보 풀의 요금제를 월 요금 오름차순(같으면 이름순)으로 모두 돌려주되, 질문이 워치·태블릿을
	 * 말하지 않았다면 기기 전용 요금제는 뺀다. 뺀 요금제가 있으면 안내 문구에 이름을 적어, LLM이 "이것이 전부"라고 오해하지 않고
	 * 필요하면 기기 전용 요금제도 안내할 수 있다고 알리게 한다.
	 *
	 * @param pool            후보 풀(요금제 전체가 들어오는 크기)
	 * @param deviceOnlyCodes 이번 질문에서 뺄 기기 전용 요금제의 plan_code. 질문이 기기를 말했으면 비어 있다.
	 * @param limit           돌려줄 최대 개수
	 */
	private PlanSearchOutcome listAllPlans(List<PlanSimilarityResult> pool, Set<String> deviceOnlyCodes, int limit) {
		List<PlanSimilarityResult> visible = pool.stream()
				.filter(candidate -> !deviceOnlyCodes.contains(candidate.planCode()))
				.sorted(Comparator.comparingInt(PlanSimilarityResult::monthlyFee).thenComparing(PlanSimilarityResult::name))
				.limit(limit)
				.toList();
		List<String> hiddenNames = pool.stream()
				.filter(candidate -> deviceOnlyCodes.contains(candidate.planCode()))
				.map(PlanSimilarityResult::name)
				.toList();
		return new PlanSearchOutcome(visible, true, listNotice(visible.size(), hiddenNames));
	}

	/** LLM에게 줄 전체 목록 안내 한 줄. 컨텍스트의 다른 안내처럼 "~할 것" 형태로 쓴다. */
	private static String listNotice(int count, List<String> hiddenDevicePlanNames) {
		StringBuilder notice = new StringBuilder("안내: 사용자가 요금제 전체 목록을 물었음. 아래 요금제 정보는 현재 안내 가능한 요금제 전체(")
				.append(count).append("종)이니 빠짐없이 월 요금이 낮은 순으로 안내할 것.");
		if (!hiddenDevicePlanNames.isEmpty()) {
			notice.append(" 기기 전용 요금제(").append(String.join(", ", hiddenDevicePlanNames))
					.append(")는 질문에서 기기를 말한 경우에만 포함하므로 이 목록에서 뺐음. 필요하면 워치·태블릿 요금제도 안내할 수 있다고 덧붙일 것.");
		}
		return notice.toString();
	}

	/** 질문이 제외한 대상 그룹("시니어 말고")에 속하는 요금제의 plan_code 집합. 제외한 그룹이 없으면 조회하지 않는다. */
	private Set<String> excludedGroupCodes(PlanQueryConditions conditions) {
		if (!conditions.hasExclusions()) {
			return Set.of();
		}
		return planLookupRepository.findTargetGroupByPlanCode().entrySet().stream()
				.filter(entry -> conditions.excludedGroups().contains(entry.getValue()))
				.map(Map.Entry::getKey)
				.collect(Collectors.toSet());
	}

	/**
	 * 원문과 요금제용 질문 어디에도 워치·태블릿 언급이 없으면 기기 전용 요금제의 plan_code 집합을, 하나라도 언급했으면 빈 집합을 돌려준다.
	 */
	private Set<String> deviceOnlyCodesToExclude(String originalQuery, String planQuery) {
		if (PlanQueryConditionExtractor.mentionsDevicePlan(originalQuery)
				|| PlanQueryConditionExtractor.mentionsDevicePlan(planQuery)) {
			return Set.of();
		}
		Map<String, String> groupByCode = planLookupRepository.findTargetGroupByPlanCode();
		return groupByCode.entrySet().stream()
				.filter(entry -> DEVICE_ONLY_GROUPS.contains(entry.getValue()))
				.map(Map.Entry::getKey)
				.collect(Collectors.toSet());
	}

	/**
	 * 모든 조건을 만족하는 요금제를 찾고, 없으면 덜 확실한 조건부터 하나씩 포기하며 다시 찾는다. 먼저 데이터 무제한 여부를 빼고,
	 * 그래도 없으면 통화·문자 정책까지 뺀다("키즈 요금제 통화 무제한 있어?"는 키즈 요금제를 대안으로 돌려준다).
	 */
	private Set<String> findMatching(PlanQueryConditions conditions) {
		Set<String> matched = planLookupRepository.findPlanCodesByConditions(conditions);
		if (matched.isEmpty() && conditions.dataPolicy() != null) {
			PlanQueryConditions relaxed = conditions.withoutDataPolicy();
			if (!relaxed.isEmpty()) {
				matched = planLookupRepository.findPlanCodesByConditions(relaxed);
			}
		}
		if (matched.isEmpty() && conditions.hasVoiceSmsConditions()) {
			PlanQueryConditions relaxed = conditions.withoutDataPolicy().withoutVoiceSmsPolicy();
			if (!relaxed.isEmpty()) {
				matched = planLookupRepository.findPlanCodesByConditions(relaxed);
			}
		}
		return matched;
	}
}
