package com.vita.search.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.service.IrrelevantQueryDetector;
import com.vita.search.service.PlanQueryConditions;
import com.vita.search.service.PlanSearchService.PlanSearchOutcome;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 검색 결과가 faq_retrieve·plan_retrieve history로 바뀔 때 후보, 판정, 건너뜀 사유가 설계대로 담기는지 확인한다. */
class RetrievalSpanHistoryTest {

	private static final RetrievalSettings SETTINGS = new RetrievalSettings(0.83, 0.79, true, 0.01, 3, 20);
	private static final RetrievalOptions OPTIONS = RetrievalOptions.forService(2);

	private static FaqSimilarityResult faq(long id, String category, String sub, double similarity) {
		return new FaqSimilarityResult(id, category, sub, "질문" + id, "답변" + id, similarity,
				LocalDateTime.of(2026, 10, 14, 10, 20));
	}

	private static PlanSimilarityResult plan(String code, double similarity) {
		return new PlanSimilarityResult(1L, code, "이름" + code, "요약", 33000, "설명 원문", similarity, null,
				"5G", "TEEN", null, null, "LIMITED", null, null, "UNLIMITED", null, "UNLIMITED", null);
	}

	private static FaqSelection emptySelection() {
		return new FaqSelection(List.of(), List.of(), List.of(), List.of(), 0.0);
	}

	private static RetrievalResult result(FaqSelection faq, PlanSearchOutcome planOutcome,
			Set<IrrelevantQueryDetector.Rule> rules, RetrievalTrace trace, TransformedQuery query) {
		return new RetrievalResult(query, faq, planOutcome, planOutcome.results(), 0.0, faq.topSimilarity(), rules,
				new StageTimings(2_000_000, 3_000_000, 4_000_000, 5_000_000, 1_000_000), FaqRetrievalContext.empty(), trace);
	}

	private static RetrievalTrace trace(float[] faqVector, float[] planVector, boolean faqSearched, boolean planSearched,
			boolean guard) {
		return new RetrievalTrace(faqVector, planVector, faqSearched, planSearched, guard, SETTINGS, OPTIONS);
	}

	@Test
	@SuppressWarnings("unchecked")
	void faqHistoryHasCandidatesWithThresholdVerdictAndTheRestOfThePool() throws Exception {
		List<FaqSimilarityResult> pool = List.of(faq(1, "해외로밍", "데이터 로밍", 0.90), faq(2, "요금", "요금조회", 0.80),
				faq(3, "기타", "기타", 0.70));
		// 상위 후보는 1, 2번이고 3번은 후보에 들지 못해 pool_rest로 간다.
		FaqSelection selection = new FaqSelection(pool, pool, pool.subList(0, 2), pool.subList(0, 1), 0.90);
		float[] vector = {0.5f, 0.25f};

		Map<String, Object> history = RetrievalSpanHistory.faq(result(selection, new PlanSearchOutcome(List.of(), false), Set.of(),
				trace(vector, null, true, false, false), new TransformedQuery("로밍 요금", "로밍 요금 안내", null)));

		assertThat(history).containsEntry("type", "FAQ").containsEntry("query", "로밍 요금 안내")
				.containsEntry("threshold", 0.83).containsEntry("top_similarity", 0.90)
				.containsEntry("embedding_shared", false).containsEntry("pool_size", 30)
				.containsEntry("context_cleared_by_rule", false);
		assertThat((List<Float>) history.get("query_embedding")).containsExactly(0.5f, 0.25f);
		assertThat((Map<String, Object>) history.get("category_boost")).containsEntry("bonus", 0.01)
				.containsEntry("matched_terms", List.of("로밍"));

		List<Map<String, Object>> candidates = (List<Map<String, Object>>) history.get("candidates");
		assertThat(candidates).hasSize(2);
		assertThat(candidates.get(0)).containsEntry("rank", 1).containsEntry("faq_id", 1L)
				.containsEntry("question", "질문1").containsEntry("answer", "답변1")
				.containsEntry("passed_threshold", true).containsEntry("doc_updated_at", "2026-10-14T10:20");
		assertThat(candidates.get(1)).containsEntry("passed_threshold", false);

		List<Map<String, Object>> rest = (List<Map<String, Object>>) history.get("pool_rest");
		assertThat(rest).hasSize(1);
		assertThat(rest.get(0)).containsEntry("rank", 3).containsEntry("faq_id", 3L).containsEntry("question", "질문3")
				.doesNotContainKey("answer");
		assertThat((Map<String, Object>) history.get("took_ms")).containsEntry("embedding", 3.0)
				.containsEntry("faq_search", 4.0).containsEntry("context", 1.0);
		assertThat(new ObjectMapper().writeValueAsString(history)).contains("\"candidates\"");
	}

	@Test
	void recordsWhichRuleClearedTheContext() {
		FaqSelection selection = new FaqSelection(List.of(), List.of(), List.of(), List.of(), 0.87);

		Map<String, Object> history = RetrievalSpanHistory.faq(result(selection, new PlanSearchOutcome(List.of(), false),
				Set.of(IrrelevantQueryDetector.Rule.PERSONAL_LOOKUP), trace(new float[] {0.1f}, null, true, false, false),
				new TransformedQuery("내 요금", "내 요금", null)));

		assertThat(history).containsEntry("context_cleared_by_rule", true)
				.containsEntry("irrelevant_rules", List.of("PERSONAL_LOOKUP"));
	}

	@Test
	void faqIsSkippedWhenTheFaqQueryWasEmpty() {
		Map<String, Object> history = RetrievalSpanHistory.faq(result(emptySelection(), new PlanSearchOutcome(List.of(), false),
				Set.of(), trace(null, null, false, false, false), new TransformedQuery("안녕", null, null)));

		assertThat(history).containsOnly(Map.entry("skipped", "QUERY_NULL"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void planHistoryHasPathConditionsAndSharesTheVectorWithFaq() {
		float[] shared = {0.3f};
		PlanQueryConditions conditions = new PlanQueryConditions(30000, 39999, null, null, "TEEN", null);
		PlanSearchOutcome outcome = new PlanSearchOutcome(List.of(plan("VITA-TEEN-30", 0.50)), true, null, conditions);

		Map<String, Object> history = RetrievalSpanHistory.plan(result(emptySelection(), outcome, Set.of(),
				trace(shared, shared, true, true, false), new TransformedQuery("틴 3만원대", "틴 3만원대", "틴 3만원대")));

		assertThat(history).containsEntry("type", "PLAN").containsEntry("path", "CONDITION")
				.containsEntry("embedding_shared", true).containsEntry("condition_matched", true)
				.containsEntry("threshold", 0.79).doesNotContainKey("query_embedding")
				.containsEntry("took_ms", Map.of("plan_search", 5.0));
		assertThat((Map<String, Object>) history.get("conditions")).containsEntry("fee_min", 30000)
				.containsEntry("fee_max", 39999).containsEntry("target_group", "TEEN").doesNotContainKey("data_policy");
		// 조건으로 좁혀진 결과는 유사도가 threshold보다 낮아도 통과로 본다
		List<Map<String, Object>> candidates = (List<Map<String, Object>>) history.get("candidates");
		assertThat(candidates.get(0)).containsEntry("plan_code", "VITA-TEEN-30").containsEntry("description", "설명 원문")
				.containsEntry("monthly_fee", 33000).containsEntry("passed_threshold", true);
	}

	@Test
	@SuppressWarnings("unchecked")
	void planPathIsVectorWithoutConditionsAndListAllWithANotice() {
		RetrievalTrace trace = trace(null, new float[] {0.4f}, false, true, false);
		TransformedQuery query = new TransformedQuery("요금제", null, "요금제");

		Map<String, Object> vector = RetrievalSpanHistory.plan(result(emptySelection(),
				new PlanSearchOutcome(List.of(plan("A", 0.60)), false), Set.of(), trace, query));
		Map<String, Object> listAll = RetrievalSpanHistory.plan(result(emptySelection(),
				new PlanSearchOutcome(List.of(plan("A", 0.60)), true, "안내: 전체 목록", null), Set.of(), trace, query));

		assertThat(vector).containsEntry("path", "VECTOR").containsKey("query_embedding");
		assertThat(((List<Map<String, Object>>) vector.get("candidates")).get(0)).containsEntry("passed_threshold", false);
		assertThat(listAll).containsEntry("path", "LIST_ALL").containsEntry("list_notice", "안내: 전체 목록");
		assertThat((Map<String, Object>) listAll.get("conditions")).isEmpty();
	}

	@Test
	void planSkipReasonsAreTheGuardOrAnEmptyQuery() {
		PlanSearchOutcome none = new PlanSearchOutcome(List.of(), false);

		Map<String, Object> guard = RetrievalSpanHistory.plan(result(emptySelection(), none, Set.of(),
				trace(new float[] {0.1f}, null, true, false, true), new TransformedQuery("인터넷 요금", "인터넷 요금", "인터넷 요금")));
		Map<String, Object> emptyQuery = RetrievalSpanHistory.plan(result(emptySelection(), none, Set.of(),
				trace(new float[] {0.1f}, null, true, false, false), new TransformedQuery("로밍 신청", "로밍 신청", null)));

		assertThat(guard).containsOnly(Map.entry("skipped", "NON_MOBILE_GUARD"));
		assertThat(emptyQuery).containsOnly(Map.entry("skipped", "QUERY_NULL"));
	}

	@Test
	void returnsNullWhenTheResultHasNoTraceInformation() {
		RetrievalResult result = new RetrievalResult(new TransformedQuery("q", "q", "q"), emptySelection(),
				new PlanSearchOutcome(List.of(), false), List.of(), 0.0, 0.0, Set.of(), new StageTimings(0, 0, 0, 0, 0),
				FaqRetrievalContext.empty());

		assertThat(RetrievalSpanHistory.faq(result)).isNull();
		assertThat(RetrievalSpanHistory.plan(result)).isNull();
	}
}
