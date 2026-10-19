package com.vita.search.pipeline;

import com.vita.embedding.EmbeddingConstants;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.service.FaqCategoryTermBooster;
import com.vita.search.service.IrrelevantQueryDetector;
import com.vita.search.service.PlanQueryConditions;
import com.vita.search.service.PlanSearchService.PlanSearchOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 검색 결과({@link RetrievalResult})를 Trace/Span에 저장할 history로 바꾼다. FAQ 검색(faq_retrieve)과 요금제 검색(plan_retrieve)을
 * 따로 만든다. 값은 문자열·숫자·불리언·Map·List로 풀어 담아서, DTO 모양이 바뀌어도 이미 저장된 기록의 형태가 흔들리지 않는다.
 *
 * <p>FAQ와 요금제 원문은 저장 당시 그대로 복사한다(나중에 관리자가 고쳐도 그때의 상태를 볼 수 있게). 후보 문서의 벡터는 담지 않고,
 * 질문 벡터만 담는다.
 */
public final class RetrievalSpanHistory {

	/** 후보 풀 중 상위 후보(원문 저장 대상)에 들지 못한 나머지를 기록할 최대 개수. 풀(보통 30개) 11~30위에 해당한다. */
	private static final int POOL_REST_LIMIT = 20;

	/** 건너뛴 이유. 질문 변환기가 해당 검색용 질문을 비워 둠. */
	public static final String SKIPPED_QUERY_NULL = "QUERY_NULL";
	/** 건너뛴 이유. 인터넷·IPTV·TV·로밍처럼 요금제 표에 없는 서비스의 요금 질문. */
	public static final String SKIPPED_NON_MOBILE_GUARD = "NON_MOBILE_GUARD";
	/** 건너뛴 이유. 위 두 경우가 아닌데 검색하지 않음(요금제를 뺀 평가 옵션 등). */
	public static final String SKIPPED_NOT_SEARCHED = "NOT_SEARCHED";

	private RetrievalSpanHistory() {
	}

	/**
	 * FAQ 검색(faq_retrieve) 단계의 history.
	 *
	 * @return 기록용 정보({@link RetrievalResult#trace()})가 없는 결과면 null. FAQ 검색을 하지 않았으면 {@code skipped} 사유만 담는다.
	 */
	public static Map<String, Object> faq(RetrievalResult result) {
		RetrievalTrace trace = result.trace();
		if (trace == null) {
			return null;
		}
		if (!trace.faqSearched()) {
			return skipped(SKIPPED_QUERY_NULL);
		}
		RetrievalSettings settings = trace.settings();
		FaqSelection faq = result.faq();

		Map<String, Object> map = new LinkedHashMap<>();
		map.put("type", "FAQ");
		map.put("query", result.query().faqQuery());
		map.put("query_embedding", vector(trace.faqVector()));
		map.put("embedding_shared", false);
		map.put("embedding_model", EmbeddingConstants.MODEL_NAME);
		map.put("pool_size", trace.options().poolSize());
		map.put("top_k", trace.options().topK());
		map.put("threshold", settings.faqThreshold());
		map.put("top_similarity", faq.topSimilarity());
		// 임베딩은 FAQ용·요금제용 질문이 다르면 두 번 부른 시간의 합이다.
		map.put("took_ms", Map.of(
				"embedding", StageTimings.toMillis(result.timings().embeddingNanos()),
				"faq_search", StageTimings.toMillis(result.timings().faqSearchNanos()),
				"context", StageTimings.toMillis(result.timings().contextNanos())));

		Map<String, Object> boost = new LinkedHashMap<>();
		boost.put("bonus", settings.categoryBoostBonus());
		boost.put("matched_terms", settings.categoryBoostBonus() > 0
				? FaqCategoryTermBooster.matchedTerms(result.query().original())
				: List.of());
		map.put("category_boost", boost);

		putRules(map, result.irrelevantRules());
		map.put("candidates", faqCandidates(faq.candidates(), settings.faqThreshold()));
		map.put("pool_rest", poolRest(faq));
		return map;
	}

	/**
	 * 요금제 검색(plan_retrieve) 단계의 history.
	 *
	 * @return 기록용 정보가 없는 결과면 null. 요금제 검색을 하지 않았으면 {@code skipped} 사유만 담는다.
	 */
	public static Map<String, Object> plan(RetrievalResult result) {
		RetrievalTrace trace = result.trace();
		if (trace == null) {
			return null;
		}
		if (!trace.planSearched()) {
			if (trace.nonMobileGuard()) {
				return skipped(SKIPPED_NON_MOBILE_GUARD);
			}
			return skipped(result.query().planQuery() == null ? SKIPPED_QUERY_NULL : SKIPPED_NOT_SEARCHED);
		}
		RetrievalSettings settings = trace.settings();
		PlanSearchOutcome outcome = result.planOutcome();
		// FAQ와 같은 질문이라 벡터를 공유했으면(같은 객체) 한 번만 저장한다.
		boolean shared = trace.faqVector() != null && trace.faqVector() == trace.planVector();

		Map<String, Object> map = new LinkedHashMap<>();
		map.put("type", "PLAN");
		map.put("query", result.query().planQuery());
		if (!shared) {
			map.put("query_embedding", vector(trace.planVector()));
		}
		map.put("embedding_shared", shared);
		map.put("embedding_model", EmbeddingConstants.MODEL_NAME);
		map.put("threshold", settings.planThreshold());
		map.put("path", planPath(outcome));
		map.put("conditions", conditions(outcome.conditions()));
		map.put("condition_matched", outcome.conditionMatched());
		map.put("top_similarity", outcome.results().stream().mapToDouble(PlanSimilarityResult::similarity).max().orElse(0.0));
		map.put("list_notice", outcome.listNotice());
		map.put("took_ms", Map.of("plan_search", StageTimings.toMillis(result.timings().planSearchNanos())));
		putRules(map, result.irrelevantRules());
		map.put("candidates", planCandidates(outcome, settings.planThreshold()));
		return map;
	}

	/** 건너뛴 단계의 history. */
	public static Map<String, Object> skipped(String reason) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("skipped", reason);
		return map;
	}

	/** 요금제를 어떻게 골랐는지: 전체 목록 요청(LIST_ALL) / 조건 매칭(CONDITION) / 벡터 유사도(VECTOR). */
	static String planPath(PlanSearchOutcome outcome) {
		if (outcome.listNotice() != null) {
			return "LIST_ALL";
		}
		return outcome.conditionMatched() ? "CONDITION" : "VECTOR";
	}

	private static void putRules(Map<String, Object> map, Set<IrrelevantQueryDetector.Rule> rules) {
		Set<String> names = new TreeSet<>();
		rules.forEach(rule -> names.add(rule.name()));
		map.put("irrelevant_rules", List.copyOf(names));
		map.put("context_cleared_by_rule", !rules.isEmpty());
	}

	private static List<Float> vector(float[] vector) {
		if (vector == null) {
			return null;
		}
		List<Float> list = new ArrayList<>(vector.length);
		for (float value : vector) {
			list.add(value);
		}
		return list;
	}

	private static List<Map<String, Object>> faqCandidates(List<FaqSimilarityResult> candidates, double threshold) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			FaqSimilarityResult c = candidates.get(i);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("rank", i + 1);
			item.put("faq_id", c.id());
			item.put("category", c.category());
			item.put("subcategory", c.subcategory());
			item.put("question", c.question());
			item.put("answer", c.answer());
			item.put("similarity", c.similarity());
			item.put("passed_threshold", c.similarity() >= threshold);
			item.put("doc_updated_at", c.updatedAt() == null ? null : c.updatedAt().toString());
			list.add(item);
		}
		return list;
	}

	/** 가산점 재정렬 순위(ranked)에서 상위 후보(candidates)에 들지 못한 항목을 제목과 유사도만 담는다. rank는 재정렬 순위다. */
	private static List<Map<String, Object>> poolRest(FaqSelection faq) {
		Set<Long> inCandidates = new LinkedHashSet<>();
		faq.candidates().forEach(c -> inCandidates.add(c.id()));
		List<Map<String, Object>> list = new ArrayList<>();
		List<FaqSimilarityResult> ranked = faq.ranked();
		for (int i = 0; i < ranked.size() && list.size() < POOL_REST_LIMIT; i++) {
			FaqSimilarityResult c = ranked.get(i);
			if (inCandidates.contains(c.id())) {
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("rank", i + 1);
			item.put("faq_id", c.id());
			item.put("question", c.question());
			item.put("similarity", c.similarity());
			list.add(item);
		}
		return list;
	}

	private static List<Map<String, Object>> planCandidates(PlanSearchOutcome outcome, double threshold) {
		List<Map<String, Object>> list = new ArrayList<>();
		List<PlanSimilarityResult> results = outcome.results();
		for (int i = 0; i < results.size(); i++) {
			PlanSimilarityResult c = results.get(i);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("rank", i + 1);
			item.put("plan_code", c.planCode());
			item.put("name", c.name());
			item.put("summary", c.summary());
			item.put("description", c.description());
			item.put("monthly_fee", c.monthlyFee());
			item.put("target_group", c.targetGroup());
			item.put("similarity", c.similarity());
			// 조건으로 좁혀진 결과는 유사도가 낮아도 threshold 없이 관련 요금제로 본다(RetrievalPipeline과 같은 기준).
			item.put("passed_threshold", outcome.conditionMatched() || c.similarity() >= threshold);
			item.put("doc_updated_at", c.updatedAt() == null ? null : c.updatedAt().toString());
			list.add(item);
		}
		return list;
	}

	/** 읽은 조건 중 값이 있는 것만 담는다. 조건이 없으면 빈 Map. */
	static Map<String, Object> conditions(PlanQueryConditions c) {
		Map<String, Object> map = new LinkedHashMap<>();
		if (c == null) {
			return map;
		}
		putIfPresent(map, "fee_min", c.feeMin());
		putIfPresent(map, "fee_max", c.feeMax());
		putIfPresent(map, "data_mb_min", c.dataMbMin());
		putIfPresent(map, "data_mb_max", c.dataMbMax());
		putIfPresent(map, "target_group", c.targetGroup());
		putIfPresent(map, "data_policy", c.dataPolicy());
		putIfPresent(map, "voice_policy", c.voicePolicy());
		putIfPresent(map, "sms_policy", c.smsPolicy());
		putIfPresent(map, "voice_minutes_min", c.voiceMinutesMin());
		putIfPresent(map, "voice_minutes_max", c.voiceMinutesMax());
		putIfPresent(map, "sms_count_min", c.smsCountMin());
		putIfPresent(map, "sms_count_max", c.smsCountMax());
		if (c.hasExclusions()) {
			map.put("excluded_groups", List.copyOf(new TreeSet<>(c.excludedGroups())));
		}
		return map;
	}

	private static void putIfPresent(Map<String, Object> map, String key, Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}
}
