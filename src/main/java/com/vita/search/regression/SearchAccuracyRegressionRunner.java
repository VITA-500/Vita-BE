package com.vita.search.regression;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqVectorSearchRepository;
import com.vita.search.repository.PlanVectorSearchRepository;
import com.vita.search.service.FaqCandidateSelector;
import com.vita.search.service.FaqCategoryTermBooster;
import com.vita.search.service.IrrelevantQueryDetector;
import com.vita.search.service.PlanSearchService;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FAQ·요금제 검색 threshold를 큰 규모(약 400문항)의 질문셋으로 재검증하는 회귀 테스트.
 * 임시로 만들고 지우는 코드가 아니라 threshold를 다시 손볼 때마다 재사용하는 도구다.
 *
 * <p>Testcontainers를 쓸 수 없는 환경이라({@link com.vita.search.regression} 패키지 전체가
 * 이 제약을 전제로 함) 기본 `./gradlew test`에는 포함하지 않고, 로컬 도커(Postgres+임베딩
 * 서버)가 떠 있을 때 {@code search.regression.enabled=true}로 켜서 수동 실행한다.
 *
 * <p>질문셋의 정답/무관 라벨은 FAQ·요금제 데이터에 종속된다. 데이터가 크게 바뀌면(예: FAQ 대량
 * 추가) 특히 무관 질문 중 UNCOVERED("FAQ에 없는 주제") 항목이 이제 정답이 있는 질문이 됐는지
 * 키워드 검색으로 재확인하고 라벨을 고친 뒤 돌린다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "search.regression", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SearchAccuracyRegressionRunner implements CommandLineRunner {

	private static final int TOP_K = 3;
	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

	private final EmbeddingProvider embeddingProvider;
	private final FaqVectorSearchRepository faqVectorSearchRepository;
	private final PlanVectorSearchRepository planVectorSearchRepository;
	private final PlanSearchService planSearchService;
	private final RegressionQueryReader queryReader;
	private final JdbcTemplate jdbcTemplate;

	/** top은 실제로 검색된 상위 결과(CSV에서 어떤 답이 나왔는지 확인하기 위해 보관). */
	private record FaqResultRow(String category, String subcategory, String style, String query,
			boolean top1Match, boolean top3Match, boolean category1Match, boolean category3Match,
			double top1Similarity, List<FaqSimilarityResult> top, boolean ambiguous) {
	}

	/**
	 * top1Match/top3Match는 planCode 1개만 정답으로 보는 엄격 기준이고, acceptable은 질문 조건을 만족하는
	 * 요금제 전체(조건 충족 기준)다. 조건이 모호한 질문은 acceptable이 null이라 조건 충족 채점에서 빠진다.
	 */
	private record PlanResultRow(String planCode, String aspect, String query,
			boolean top1Match, boolean top3Match, double top1Similarity, List<PlanSimilarityResult> top,
			Set<String> acceptable, List<PlanSimilarityResult> vectorOnlyTop) {

		boolean graded() {
			return acceptable != null;
		}

		/** 조건 매칭 없이 벡터 유사도만 썼을 때(개선 전)의 1등이 조건을 만족하는지. */
		boolean baselineTop1Ok() {
			return graded() && !vectorOnlyTop.isEmpty() && acceptable.contains(vectorOnlyTop.get(0).planCode());
		}

		boolean top1Ok() {
			return graded() && !top.isEmpty() && acceptable.contains(top.get(0).planCode());
		}

		long top3OkCount() {
			return graded() ? top.stream().filter(r -> acceptable.contains(r.planCode())).count() : 0;
		}
	}

	/** 요금제 테이블의 정답 계산용 속성(plans 테이블에서 읽는다). */
	private record PlanAttributes(String planCode, String targetGroup, int monthlyFee, String dataPolicy,
			Long baseDataMb, String voicePolicy, String smsPolicy) {

		boolean satisfies(PlanRegressionQuery.PlanExpectation e) {
			return (e.planCode() == null || e.planCode().equals(planCode))
					&& (e.targetGroup() == null || e.targetGroup().equals(targetGroup))
					&& (e.monthlyFee() == null || e.monthlyFee() == monthlyFee)
					&& (e.dataMb() == null || e.dataMb().equals(baseDataMb))
					&& (e.dataPolicy() == null || e.dataPolicy().equals(dataPolicy))
					&& (e.voicePolicy() == null || e.voicePolicy().equals(voicePolicy))
					&& (e.smsPolicy() == null || e.smsPolicy().equals(smsPolicy));
		}
	}

	/** planTop1은 요금제 쪽 1등(없으면 null). */
	private record NegativeResultRow(String topic, String query, double faqTop1Similarity, double planTop1Similarity,
			List<FaqSimilarityResult> faqTop, PlanSimilarityResult planTop1) {
	}

	@Override
	public void run(String... args) {
		List<FaqRegressionQuery> faqQueries = queryReader.read(
				new ClassPathResource("data/regression/faq_regression_queries.jsonl"), FaqRegressionQuery.class);
		List<PlanRegressionQuery> planQueries = queryReader.read(
				new ClassPathResource("data/regression/plan_regression_queries.jsonl"), PlanRegressionQuery.class);
		List<NegativeRegressionQuery> negativeQueries = queryReader.read(
				new ClassPathResource("data/regression/negative_regression_queries.jsonl"), NegativeRegressionQuery.class);

		List<FaqResultRow> faqResults = faqQueries.stream().map(this::evaluateFaqQuery).toList();
		List<PlanAttributes> planCatalog = loadPlanCatalog();
		List<PlanResultRow> planResults = planQueries.stream().map(q -> evaluatePlanQuery(q, planCatalog)).toList();
		List<NegativeResultRow> negativeResults = negativeQueries.stream().map(this::evaluateNegativeQuery).toList();

		printFaqSummary(faqResults);
		printBoostExperiment(faqQueries, negativeQueries);
		printBoostValidation(queryReader.read(
				new ClassPathResource("data/regression/faq_boost_validation_queries.jsonl"), FaqRegressionQuery.class));
		printPlanSummary(planResults);
		printNegativeSummary(negativeResults);
		printRuleDetectorSummary(negativeResults, faqQueries, planQueries);
		printRuleValidationSummary(queryReader.read(
				new ClassPathResource("data/regression/rule_validation_queries.jsonl"), RuleValidationQuery.class));
		writeCsvReport(faqResults, planResults, negativeResults);

		log.info("REGRESSION DONE faq={} plan={} negative={}", faqResults.size(), planResults.size(), negativeResults.size());
	}

	private FaqResultRow evaluateFaqQuery(FaqRegressionQuery item) {
		float[] vector = embeddingProvider.embedQuery(item.query());
		// 실제 검색(FaqRetrievalServiceImpl)과 같은 방식으로, 후보를 넉넉히 가져와 중복 답변을 걷어낸 뒤 자른다.
		List<FaqSimilarityResult> pool = faqVectorSearchRepository.searchBySimilarity(
				vector, FaqStatus.ACTIVE, 0.0, FaqCandidateSelector.poolSize(TOP_K));
		List<FaqSimilarityResult> results = FaqCandidateSelector.selectDistinct(pool, TOP_K);
		boolean top1Match = !results.isEmpty() && matchesFaq(results.get(0), item);
		boolean top3Match = results.stream().anyMatch(r -> matchesFaq(r, item));
		// 세부분류는 무시하고 카테고리만 맞는지도 본다 — 오답이 "엉뚱한 주제"인지 "같은 카테고리의 이웃 세부분류"인지 구분하기 위해서다.
		boolean category1Match = !results.isEmpty() && results.get(0).category().equals(item.category());
		boolean category3Match = results.stream().anyMatch(r -> r.category().equals(item.category()));
		double top1Similarity = results.isEmpty() ? 0.0 : results.get(0).similarity();
		return new FaqResultRow(item.category(), item.subcategory(), item.style(), item.query(),
				top1Match, top3Match, category1Match, category3Match, top1Similarity, results, item.isAmbiguous());
	}

	private boolean matchesFaq(FaqSimilarityResult result, FaqRegressionQuery item) {
		return result.category().equals(item.category()) && result.subcategory().equals(item.subcategory());
	}

	private List<PlanAttributes> loadPlanCatalog() {
		return jdbcTemplate.query("""
				SELECT plan_code, target_group, monthly_fee, data_policy, base_data_mb, voice_policy, sms_policy
				FROM plans
				WHERE status = 'ACTIVE'
				""",
				(rs, rowNum) -> new PlanAttributes(
						rs.getString("plan_code"),
						rs.getString("target_group"),
						rs.getInt("monthly_fee"),
						rs.getString("data_policy"),
						rs.getObject("base_data_mb", Long.class),
						rs.getString("voice_policy"),
						rs.getString("sms_policy")));
	}

	private PlanResultRow evaluatePlanQuery(PlanRegressionQuery item, List<PlanAttributes> catalog) {
		float[] vector = embeddingProvider.embedQuery(item.query());
		// 실제 검색(FaqRetrievalServiceImpl)과 같은 PlanSearchService를 쓰고, 개선 전 비교용으로 벡터만 쓴 결과도 함께 보관한다.
		List<PlanSimilarityResult> results = planSearchService.search(item.query(), vector, TOP_K).results();
		List<PlanSimilarityResult> vectorOnly = planVectorSearchRepository.searchBySimilarity(vector, 0.0, TOP_K);
		boolean top1Match = !results.isEmpty() && results.get(0).planCode().equals(item.planCode());
		boolean top3Match = results.stream().anyMatch(r -> r.planCode().equals(item.planCode()));
		double top1Similarity = results.isEmpty() ? 0.0 : results.get(0).similarity();
		Set<String> acceptable = item.expect() == null ? null : catalog.stream()
				.filter(plan -> plan.satisfies(item.expect()))
				.map(PlanAttributes::planCode)
				.collect(Collectors.toCollection(TreeSet::new));
		return new PlanResultRow(item.planCode(), item.aspect(), item.query(), top1Match, top3Match, top1Similarity,
				results, acceptable, vectorOnly);
	}

	private NegativeResultRow evaluateNegativeQuery(NegativeRegressionQuery item) {
		float[] vector = embeddingProvider.embedQuery(item.query());
		List<FaqSimilarityResult> faqPool = faqVectorSearchRepository.searchBySimilarity(
				vector, FaqStatus.ACTIVE, 0.0, FaqCandidateSelector.poolSize(TOP_K));
		List<FaqSimilarityResult> faqResults = FaqCandidateSelector.selectDistinct(faqPool, TOP_K);
		List<PlanSimilarityResult> planResults = planVectorSearchRepository.searchBySimilarity(vector, 0.0, 1);
		double faqTop1 = faqResults.isEmpty() ? 0.0 : faqResults.get(0).similarity();
		double planTop1 = planResults.isEmpty() ? 0.0 : planResults.get(0).similarity();
		PlanSimilarityResult planTop1Result = planResults.isEmpty() ? null : planResults.get(0);
		return new NegativeResultRow(item.topic(), item.query(), faqTop1, planTop1, faqResults, planTop1Result);
	}

	/** 가산점 실험에서 쓰는, 질문 하나와 그 질문의 FAQ 후보 풀(유사도 내림차순). */
	private record PooledQuery(FaqRegressionQuery item, List<FaqSimilarityResult> pool) {
	}

	/** 가산점 실험 한 번(bonus 하나)의 질문별 결과. */
	private record BoostOutcome(boolean top1, boolean top3, boolean category1, List<FaqSimilarityResult> results) {
	}

	/**
	 * 분류 이름 가산점({@link FaqCategoryTermBooster})의 효과 실험. 가산점 크기를 바꿔 가며 같은 후보 풀을 다시 정렬해
	 * (1) 정확도가 얼마나 변하는지, (2) 새로 맞게 되는 질문(고침)과 맞다가 틀리게 되는 질문(망가짐)이 각각 몇 개인지,
	 * (3) 무관 질문에서 threshold를 넘는 후보가 늘지 않는지를 센다. 이득이 확인된 값만 실제 검색에 적용한다.
	 */
	private void printBoostExperiment(List<FaqRegressionQuery> faqQueries, List<NegativeRegressionQuery> negativeQueries) {
		List<PooledQuery> faqPools = faqQueries.stream().map(q -> new PooledQuery(q, fetchFaqPool(q.query()))).toList();
		List<List<FaqSimilarityResult>> negativePools = negativeQueries.stream()
				.map(q -> fetchFaqPool(q.query())).toList();

		List<BoostOutcome> baseline = faqPools.stream().map(p -> evaluateWithBonus(p, 0.0)).toList();
		log.info("=== 실험: 분류 이름 가산점 (FAQ {}문항 / 무관 {}문항) ===", faqQueries.size(), negativeQueries.size());

		for (double bonus : new double[] {0.0, 0.005, 0.01, 0.02, 0.03, 0.05, 0.08, 0.12}) {
			List<BoostOutcome> outcomes = faqPools.stream().map(p -> evaluateWithBonus(p, bonus)).toList();

			int fixed = 0;
			int broken = 0;
			List<String> fixedQueries = new ArrayList<>();
			List<String> brokenQueries = new ArrayList<>();
			long clearTotal = 0;
			long clearTop1 = 0;
			for (int i = 0; i < faqPools.size(); i++) {
				FaqRegressionQuery item = faqPools.get(i).item();
				BoostOutcome before = baseline.get(i);
				BoostOutcome after = outcomes.get(i);
				if (!before.top1() && after.top1()) {
					fixed++;
					fixedQueries.add(describeChange(item, before, after));
				}
				if (before.top1() && !after.top1()) {
					broken++;
					brokenQueries.add(describeChange(item, before, after));
				}
				if (!item.isAmbiguous()) {
					clearTotal++;
					clearTop1 += after.top1() ? 1 : 0;
				}
			}

			// 무관 질문: 다시 정렬한 뒤 1등의 원래 유사도가 threshold(0.83) 이상인 질문 수(늘면 오탐이 느는 것).
			long negativeLeaks = 0;
			for (int i = 0; i < negativePools.size(); i++) {
				List<FaqSimilarityResult> top = FaqCandidateSelector.selectDistinct(
						FaqCategoryTermBooster.rerank(negativeQueries.get(i).query(), negativePools.get(i), bonus), TOP_K);
				if (!top.isEmpty() && top.get(0).similarity() >= 0.83) {
					negativeLeaks++;
				}
			}

			log.info("[가산점 {}] Top-1 {}%  Top-3 {}%  카테고리 Top-1 {}%  단서 있는 질문 Top-1 {}%  | 고침 {}  망가짐 {} | 무관 질문 1등이 0.83 이상 {}개",
					String.format("%.3f", bonus),
					String.format("%.1f", 100.0 * outcomes.stream().filter(BoostOutcome::top1).count() / outcomes.size()),
					String.format("%.1f", 100.0 * outcomes.stream().filter(BoostOutcome::top3).count() / outcomes.size()),
					String.format("%.1f", 100.0 * outcomes.stream().filter(BoostOutcome::category1).count() / outcomes.size()),
					String.format("%.1f", clearTotal == 0 ? 0.0 : 100.0 * clearTop1 / clearTotal),
					fixed, broken, negativeLeaks);
			if (bonus == 0.01 || bonus == 0.02 || bonus == 0.05) {
				fixedQueries.forEach(s -> log.info("    [가산점 {}] 고침: {}", String.format("%.3f", bonus), s));
				brokenQueries.forEach(s -> log.info("    [가산점 {}] 망가짐: {}", String.format("%.3f", bonus), s));
			}
		}
	}

	/**
	 * 가산점 규칙을 만들 때 보지 않은 검증 질문({@code faq_boost_validation_queries.jsonl})으로 일반화를 확인한다.
	 * 상품 단어가 있는 질문(개선 기대), 상품 단어가 없는 질문(영향 없어야 함), 상품 단어가 있지만 정답이 다른 분류인 질문
	 * (망가지면 안 됨)이 섞여 있다. 가산점마다 정확도와 고침/망가짐 목록을 출력한다.
	 */
	private void printBoostValidation(List<FaqRegressionQuery> validation) {
		List<PooledQuery> pools = validation.stream().map(q -> new PooledQuery(q, fetchFaqPool(q.query()))).toList();
		List<BoostOutcome> baseline = pools.stream().map(p -> evaluateWithBonus(p, 0.0)).toList();
		log.info("=== 검증: 규칙 작성 때 보지 않은 질문 {}문항으로 분류 이름 가산점 확인 ===", validation.size());
		for (int i = 0; i < pools.size(); i++) {
			if (!baseline.get(i).top1()) {
				FaqRegressionQuery item = pools.get(i).item();
				FaqSimilarityResult top1 = baseline.get(i).results().get(0);
				log.info("    [검증 기준선] Top-1 오답: [{}/{}] \"{}\" → 1등={}/{} ({})", item.category(), item.subcategory(), item.query(),
						top1.category(), top1.subcategory(), String.format("%.4f", top1.similarity()));
			}
		}

		for (double bonus : new double[] {0.0, 0.01, 0.02, 0.05}) {
			List<BoostOutcome> outcomes = pools.stream().map(p -> evaluateWithBonus(p, bonus)).toList();
			int fixed = 0;
			int broken = 0;
			for (int i = 0; i < pools.size(); i++) {
				FaqRegressionQuery item = pools.get(i).item();
				boolean before = baseline.get(i).top1();
				boolean after = outcomes.get(i).top1();
				if (!before && after) {
					fixed++;
					log.info("    [검증 가산점 {}] 고침: {}", String.format("%.3f", bonus), describeChange(item, baseline.get(i), outcomes.get(i)));
				}
				if (before && !after) {
					broken++;
					log.info("    [검증 가산점 {}] 망가짐: {}", String.format("%.3f", bonus), describeChange(item, baseline.get(i), outcomes.get(i)));
				}
			}
			log.info("[검증 가산점 {}] Top-1 {}% ({}/{})  Top-3 {}%  카테고리 Top-1 {}%  | 고침 {}  망가짐 {}",
					String.format("%.3f", bonus),
					String.format("%.1f", 100.0 * outcomes.stream().filter(BoostOutcome::top1).count() / outcomes.size()),
					outcomes.stream().filter(BoostOutcome::top1).count(), outcomes.size(),
					String.format("%.1f", 100.0 * outcomes.stream().filter(BoostOutcome::top3).count() / outcomes.size()),
					String.format("%.1f", 100.0 * outcomes.stream().filter(BoostOutcome::category1).count() / outcomes.size()),
					fixed, broken);
		}
	}

	/** 질문 하나의 FAQ 후보 풀을 가져온다(실제 검색과 같은 후보 수). */
	private List<FaqSimilarityResult> fetchFaqPool(String query) {
		float[] vector = embeddingProvider.embedQuery(query);
		return faqVectorSearchRepository.searchBySimilarity(vector, FaqStatus.ACTIVE, 0.0, FaqCandidateSelector.poolSize(TOP_K));
	}

	private BoostOutcome evaluateWithBonus(PooledQuery pooled, double bonus) {
		FaqRegressionQuery item = pooled.item();
		List<FaqSimilarityResult> results = FaqCandidateSelector.selectDistinct(
				FaqCategoryTermBooster.rerank(item.query(), pooled.pool(), bonus), TOP_K);
		boolean top1 = !results.isEmpty() && matchesFaq(results.get(0), item);
		boolean top3 = results.stream().anyMatch(r -> matchesFaq(r, item));
		boolean category1 = !results.isEmpty() && results.get(0).category().equals(item.category());
		return new BoostOutcome(top1, top3, category1, results);
	}

	private String describeChange(FaqRegressionQuery item, BoostOutcome before, BoostOutcome after) {
		FaqSimilarityResult beforeTop = before.results().get(0);
		FaqSimilarityResult afterTop = after.results().get(0);
		return String.format("[%s/%s][%s] \"%s\" : %s/%s → %s/%s", item.category(), item.subcategory(), item.style(), item.query(),
				beforeTop.category(), beforeTop.subcategory(), afterTop.category(), afterTop.subcategory());
	}

	/** rows 중 조건을 만족하는 비율(%). rows가 비어 있으면 0. */
	private static double percent(List<FaqResultRow> rows, java.util.function.Predicate<FaqResultRow> condition) {
		return rows.isEmpty() ? 0.0 : 100.0 * rows.stream().filter(condition).count() / rows.size();
	}

	private void printFaqSummary(List<FaqResultRow> results) {
		double top1Rate = 100.0 * results.stream().filter(FaqResultRow::top1Match).count() / results.size();
		double top3Rate = 100.0 * results.stream().filter(FaqResultRow::top3Match).count() / results.size();
		double minTop1SimilarityOfMatches = results.stream()
				.filter(FaqResultRow::top1Match)
				.mapToDouble(FaqResultRow::top1Similarity)
				.min().orElse(0.0);

		log.info("=== FAQ 회귀 테스트 ({}문항) ===", results.size());
		log.info("Top-1 정확도: {}%  Top-3 정확도: {}%", String.format("%.1f", top1Rate), String.format("%.1f", top3Rate));
		log.info("Top-1 정답 중 최저 유사도(현재 threshold=0.83과 비교): {}", String.format("%.4f", minTop1SimilarityOfMatches));
		log.info("카테고리 기준(세부분류 무시) Top-1 정확도: {}%  Top-3 정확도: {}%",
				String.format("%.1f", percent(results, FaqResultRow::category1Match)),
				String.format("%.1f", percent(results, FaqResultRow::category3Match)));

		List<FaqResultRow> clear = results.stream().filter(r -> !r.ambiguous()).toList();
		List<FaqResultRow> ambiguous = results.stream().filter(FaqResultRow::ambiguous).toList();
		if (!ambiguous.isEmpty()) {
			log.info("[단서 있는 질문] {}문항 Top-1 정확도: {}%  Top-3 정확도: {}%  (카테고리 기준 Top-1 {}%)",
					clear.size(), String.format("%.1f", percent(clear, FaqResultRow::top1Match)),
					String.format("%.1f", percent(clear, FaqResultRow::top3Match)),
					String.format("%.1f", percent(clear, FaqResultRow::category1Match)));
			log.info("[모호한 질문(서비스·상품 단서 없음)] {}문항 Top-1 정확도: {}%  Top-3 정확도: {}%  (카테고리 기준 Top-1 {}%)",
					ambiguous.size(), String.format("%.1f", percent(ambiguous, FaqResultRow::top1Match)),
					String.format("%.1f", percent(ambiguous, FaqResultRow::top3Match)),
					String.format("%.1f", percent(ambiguous, FaqResultRow::category1Match)));
		}

		Map<String, List<FaqResultRow>> byStyle = results.stream().collect(Collectors.groupingBy(FaqResultRow::style));
		byStyle.entrySet().stream()
				.sorted(Comparator.comparing(Map.Entry::getKey))
				.forEach(entry -> {
					List<FaqResultRow> rows = entry.getValue();
					double rate = percent(rows, FaqResultRow::top1Match);
					log.info("  스타일별 [{}] Top-1 정확도: {}% ({}문항)", entry.getKey(), String.format("%.1f", rate), rows.size());
					List<FaqResultRow> rowsClear = rows.stream().filter(r -> !r.ambiguous()).toList();
					List<FaqResultRow> rowsAmbiguous = rows.stream().filter(FaqResultRow::ambiguous).toList();
					if (!rowsAmbiguous.isEmpty()) {
						log.info("      └ 단서 있음 {}문항 Top-1 {}% / 모호 {}문항 Top-1 {}%",
								rowsClear.size(), String.format("%.1f", percent(rowsClear, FaqResultRow::top1Match)),
								rowsAmbiguous.size(), String.format("%.1f", percent(rowsAmbiguous, FaqResultRow::top1Match)));
					}
				});

		// 단서가 있는데 Top-1이 틀린 질문은 질문 모호함이 아니라 검색 자체의 약점이라 개선 대상이다.
		clear.stream().filter(r -> !r.top1Match()).forEach(r -> {
			FaqSimilarityResult top1 = r.top().isEmpty() ? null : r.top().get(0);
			log.info("  단서 있는데 Top-1 오답: [{}/{}][{}] \"{}\" → 1등={} (유사도 {})",
					r.category(), r.subcategory(), r.style(), r.query(),
					top1 == null ? "-" : top1.category() + "/" + top1.subcategory(),
					top1 == null ? "-" : String.format("%.4f", top1.similarity()));
		});

		results.stream().filter(r -> !r.top3Match()).forEach(r ->
				log.info("  미스(Top-3에도 없음): [{}/{}][{}] \"{}\" (top1 유사도={})",
						r.category(), r.subcategory(), r.style(), r.query(), String.format("%.4f", r.top1Similarity())));
	}

	private void printPlanSummary(List<PlanResultRow> results) {
		double top1Rate = 100.0 * results.stream().filter(PlanResultRow::top1Match).count() / results.size();
		double top3Rate = 100.0 * results.stream().filter(PlanResultRow::top3Match).count() / results.size();
		double minTop1SimilarityOfMatches = results.stream()
				.filter(PlanResultRow::top1Match)
				.mapToDouble(PlanResultRow::top1Similarity)
				.min().orElse(0.0);

		log.info("=== 요금제 회귀 테스트 ({}문항) ===", results.size());
		log.info("[엄격 기준: 질문마다 정답 요금제 1개로 고정] Top-1 정확도: {}%  Top-3 정확도: {}%",
				String.format("%.1f", top1Rate), String.format("%.1f", top3Rate));
		log.info("Top-1 정답 중 최저 유사도(현재 threshold=0.81과 비교): {}", String.format("%.4f", minTop1SimilarityOfMatches));
		printPlanConditionSummary(results);

		Map<String, List<PlanResultRow>> byAspect = results.stream().collect(Collectors.groupingBy(PlanResultRow::aspect));
		byAspect.entrySet().stream()
				.sorted(Comparator.comparing(Map.Entry::getKey))
				.forEach(entry -> {
					List<PlanResultRow> rows = entry.getValue();
					double rate = 100.0 * rows.stream().filter(PlanResultRow::top1Match).count() / rows.size();
					log.info("  유형별 [{}] Top-1 정확도: {}% ({}문항)", entry.getKey(), String.format("%.1f", rate), rows.size());
				});

		results.stream().filter(r -> !r.top3Match()).forEach(r ->
				log.info("  엄격 기준 미스(Top-3에도 없음): [{}][{}] \"{}\" (top1 유사도={})",
						r.planCode(), r.aspect(), r.query(), String.format("%.4f", r.top1Similarity())));
	}

	/**
	 * 질문의 조건을 만족하는 요금제를 모두 정답으로 보는 채점. "시니어 요금제 있어?"에 시니어 8이든
	 * 시니어 20이든 나오면 정답이고, "3만1천원 요금제"는 월 31,000원 요금제만 정답이다.
	 */
	private void printPlanConditionSummary(List<PlanResultRow> results) {
		List<PlanResultRow> graded = results.stream().filter(PlanResultRow::graded).toList();
		long top1Ok = graded.stream().filter(PlanResultRow::top1Ok).count();
		long top3Ok = graded.stream().mapToLong(PlanResultRow::top3OkCount).sum();
		long top3Total = graded.stream().mapToLong(r -> r.top().size()).sum();
		long baselineTop1Ok = graded.stream().filter(PlanResultRow::baselineTop1Ok).count();

		log.info("[조건 충족 기준·개선 전(벡터 유사도만)] Top-1 정확도: {}% ({}/{}문항)",
				String.format("%.1f", 100.0 * baselineTop1Ok / graded.size()), baselineTop1Ok, graded.size());

		log.info("[조건 충족 기준] 채점 {}문항(조건이 모호해 제외 {}문항): Top-1 정확도: {}%  Top-3 중 조건 충족 비율: {}%",
				graded.size(), results.size() - graded.size(),
				String.format("%.1f", 100.0 * top1Ok / graded.size()),
				String.format("%.1f", 100.0 * top3Ok / top3Total));

		graded.stream().collect(Collectors.groupingBy(PlanResultRow::aspect)).entrySet().stream()
				.sorted(Comparator.comparing(Map.Entry::getKey))
				.forEach(entry -> {
					List<PlanResultRow> rows = entry.getValue();
					long ok = rows.stream().filter(PlanResultRow::top1Ok).count();
					log.info("  조건 충족 유형별 [{}] Top-1 정확도: {}% ({}문항)", entry.getKey(),
							String.format("%.1f", 100.0 * ok / rows.size()), rows.size());
				});

		graded.stream().filter(r -> !r.top1Ok()).forEach(r ->
				log.info("  조건 미충족 1등: [{}] \"{}\" → 1등={} (정답 후보 {}개: {})",
						r.aspect(), r.query(), r.top().get(0).name(), r.acceptable().size(), r.acceptable()));
	}

	private void printNegativeSummary(List<NegativeResultRow> results) {
		double maxFaqSimilarity = results.stream().mapToDouble(NegativeResultRow::faqTop1Similarity).max().orElse(0.0);
		double maxPlanSimilarity = results.stream().mapToDouble(NegativeResultRow::planTop1Similarity).max().orElse(0.0);
		long faqFalsePositives = results.stream().filter(r -> r.faqTop1Similarity() >= 0.83).count();
		long planFalsePositives = results.stream().filter(r -> r.planTop1Similarity() >= 0.81).count();

		log.info("=== 무관 질문 회귀 테스트 ({}문항) ===", results.size());
		log.info("FAQ 쪽 최고 유사도(현재 threshold=0.83과 비교): {}  (0.83 이상으로 잘못 걸린 개수: {})",
				String.format("%.4f", maxFaqSimilarity), faqFalsePositives);
		log.info("요금제 쪽 최고 유사도(현재 threshold=0.81과 비교): {}  (0.81 이상으로 잘못 걸린 개수: {})",
				String.format("%.4f", maxPlanSimilarity), planFalsePositives);

		Map<String, List<NegativeResultRow>> byTopic = results.stream().collect(Collectors.groupingBy(NegativeResultRow::topic));
		byTopic.entrySet().stream()
				.sorted(Comparator.comparing(Map.Entry::getKey))
				.forEach(entry -> {
					double maxFaq = entry.getValue().stream().mapToDouble(NegativeResultRow::faqTop1Similarity).max().orElse(0.0);
					double maxPlan = entry.getValue().stream().mapToDouble(NegativeResultRow::planTop1Similarity).max().orElse(0.0);
					log.info("  주제별 [{}] FAQ 최고={} 요금제 최고={} ({}문항)",
							entry.getKey(), String.format("%.4f", maxFaq), String.format("%.4f", maxPlan), entry.getValue().size());
				});
	}

	/**
	 * 규칙 기반 무관 질문 판별({@link IrrelevantQueryDetector})의 손익 측정. 규칙마다 (1) 무관 질문을 몇 개 잡는지,
	 * 그중 FAQ threshold를 넘어 실제로 새던 질문을 몇 개 막는지, (2) 정상 질문을 몇 개 잘못 잡는지를 출력한다.
	 * 정상 질문은 회귀 FAQ·요금제 질문셋 외에, 실제 사용자 표현에 가까운 FAQ DB의 질문 전체도 함께 본다.
	 */
	private void printRuleDetectorSummary(List<NegativeResultRow> negatives, List<FaqRegressionQuery> faqQueries,
			List<PlanRegressionQuery> planQueries) {
		List<String> faqDbQuestions = jdbcTemplate.queryForList(
				"SELECT question FROM faqs WHERE status = 'ACTIVE'", String.class);

		log.info("=== 규칙 기반 무관 질문 판별 측정 (무관 {}문항 / 정상: 회귀 FAQ {}·요금제 {}·FAQ DB 질문 {}) ===",
				negatives.size(), faqQueries.size(), planQueries.size(), faqDbQuestions.size());

		long leaks = negatives.stream().filter(r -> r.faqTop1Similarity() >= 0.83).count();
		for (IrrelevantQueryDetector.Rule rule : IrrelevantQueryDetector.Rule.values()) {
			List<NegativeResultRow> caught = negatives.stream()
					.filter(r -> IrrelevantQueryDetector.detect(r.query()).contains(rule)).toList();
			long caughtLeaks = caught.stream().filter(r -> r.faqTop1Similarity() >= 0.83).count();
			log.info("[{}] 무관 질문 {}/{}개 잡음 (그중 threshold를 넘어 새던 질문 {}/{}개 차단)",
					rule, caught.size(), negatives.size(), caughtLeaks, leaks);
			caught.stream().collect(Collectors.groupingBy(NegativeResultRow::topic, TreeMap::new, Collectors.counting()))
					.forEach((topic, count) -> log.info("    무관 주제 [{}] {}개", topic, count));

			List<String> falseFaq = faqQueries.stream().map(FaqRegressionQuery::query)
					.filter(q -> IrrelevantQueryDetector.detect(q).contains(rule)).toList();
			List<String> falsePlan = planQueries.stream().map(PlanRegressionQuery::query)
					.filter(q -> IrrelevantQueryDetector.detect(q).contains(rule)).toList();
			List<String> falseDb = faqDbQuestions.stream()
					.filter(q -> IrrelevantQueryDetector.detect(q).contains(rule)).toList();
			log.info("    정상 질문 오탐: 회귀 FAQ {}/{}, 요금제 {}/{}, FAQ DB 질문 {}/{}",
					falseFaq.size(), faqQueries.size(), falsePlan.size(), planQueries.size(),
					falseDb.size(), faqDbQuestions.size());
			falseFaq.forEach(q -> log.info("      오탐(회귀 FAQ): {}", q));
			falsePlan.forEach(q -> log.info("      오탐(회귀 요금제): {}", q));
			falseDb.stream().limit(30).forEach(q -> log.info("      오탐(FAQ DB): {}", q));
		}

		List<NegativeResultRow> anyRuleCaught = negatives.stream()
				.filter(r -> !IrrelevantQueryDetector.detect(r.query()).isEmpty()).toList();
		long anyLeaksBlocked = anyRuleCaught.stream().filter(r -> r.faqTop1Similarity() >= 0.83).count();
		log.info("[전체 규칙 합산] 무관 질문 {}/{}개 잡음, threshold를 넘어 새던 {}개 중 {}개 차단, 남는 누수 {}개",
				anyRuleCaught.size(), negatives.size(), leaks, anyLeaksBlocked, leaks - anyLeaksBlocked);
		negatives.stream()
				.filter(r -> r.faqTop1Similarity() >= 0.83 && IrrelevantQueryDetector.detect(r.query()).isEmpty())
				.sorted(Comparator.comparingDouble(NegativeResultRow::faqTop1Similarity).reversed())
				.forEach(r -> log.info("    남는 누수 [{}] {} (유사도={})",
						r.topic(), r.query(), String.format("%.4f", r.faqTop1Similarity())));
	}

	/**
	 * 규칙을 만들 때 보지 않은 검증 문항({@code rule_validation_queries.jsonl})으로 규칙의 일반화 성능을 잰다.
	 * PERSONAL/COMPETITOR는 규칙에 걸려야 하고(놓친 질문을 출력), NORMAL은 걸리면 안 된다(오탐을 출력).
	 * 걸려야 하는 질문 중 FAQ threshold를 넘어 실제로 새던 질문을 몇 개 막는지도 함께 센다.
	 */
	private void printRuleValidationSummary(List<RuleValidationQuery> queries) {
		log.info("=== 규칙 검증 세트 측정 (규칙 작성 때 보지 않은 {}문항) ===", queries.size());

		for (String kind : List.of("PERSONAL", "COMPETITOR", "NORMAL")) {
			List<RuleValidationQuery> group = queries.stream().filter(q -> q.kind().equals(kind)).toList();
			boolean shouldCatch = !kind.equals("NORMAL");

			long caught = 0;
			long leaks = 0;
			long leaksBlocked = 0;
			for (RuleValidationQuery q : group) {
				Set<IrrelevantQueryDetector.Rule> rules = IrrelevantQueryDetector.detect(q.query());
				boolean flagged = kind.equals("PERSONAL")
						? rules.contains(IrrelevantQueryDetector.Rule.PERSONAL_LOOKUP)
						: kind.equals("COMPETITOR")
								? rules.contains(IrrelevantQueryDetector.Rule.COMPETITOR_BRAND)
										|| rules.contains(IrrelevantQueryDetector.Rule.COMPETITOR_GENERIC)
								: !rules.isEmpty();
				double similarity = faqTop1Similarity(q.query());
				boolean leak = similarity >= 0.83;

				if (flagged) {
					caught++;
				}
				if (shouldCatch && leak) {
					leaks++;
					if (flagged) {
						leaksBlocked++;
					}
				}
				if (shouldCatch && !flagged) {
					log.info("    놓침 [{}] {} (FAQ 유사도={}{})", kind, q.query(),
							String.format("%.4f", similarity), leak ? ", threshold 넘어 샘" : "");
				}
				if (!shouldCatch && flagged) {
					log.info("    오탐 [NORMAL] {} → {}", q.query(), rules);
				}
			}

			if (shouldCatch) {
				log.info("[{}] 규칙에 걸림 {}/{}개, 그중 threshold를 넘어 새던 질문 {}개 중 {}개 차단",
						kind, caught, group.size(), leaks, leaksBlocked);
			} else {
				log.info("[NORMAL] 정상 질문 오탐 {}/{}개", caught, group.size());
			}
		}
	}

	/** 질문의 FAQ 1등 유사도(실제 검색과 같은 후보 풀·중복 제거 적용). 결과가 없으면 0. */
	private double faqTop1Similarity(String query) {
		float[] vector = embeddingProvider.embedQuery(query);
		List<FaqSimilarityResult> pool = faqVectorSearchRepository.searchBySimilarity(
				vector, FaqStatus.ACTIVE, 0.0, FaqCandidateSelector.poolSize(TOP_K));
		List<FaqSimilarityResult> results = FaqCandidateSelector.selectDistinct(pool, TOP_K);
		return results.isEmpty() ? 0.0 : results.get(0).similarity();
	}

	private void writeCsvReport(List<FaqResultRow> faqResults, List<PlanResultRow> planResults,
			List<NegativeResultRow> negativeResults) {
		Path dir = Path.of("build", "regression-report");
		String filename = "search-accuracy-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".csv";
		Path file = dir.resolve(filename);
		try {
			Files.createDirectories(dir);
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				// 엑셀은 BOM이 없는 UTF-8 CSV의 한글을 깨뜨려서 읽으므로, 파일 맨 앞에 UTF-8 BOM을 붙인다.
				writer.write('﻿');
				writer.write("type,label,styleOrAspect,query,top1Match,top3Match,top1Similarity,"
						+ "faqTop1Similarity,planTop1Similarity,planTop1Name,"
						+ "r1_similarity,r1_label,r1_question,r1_answer,"
						+ "r2_similarity,r2_label,r2_question,r2_answer,"
						+ "r3_similarity,r3_label,r3_question,r3_answer,"
						+ "conditionTop1Ok,acceptablePlans,ambiguous\n");
				for (FaqResultRow r : faqResults) {
					writeCsvRow(writer, "FAQ", r.category() + "/" + r.subcategory(), r.style(), r.query(),
							String.valueOf(r.top1Match()), String.valueOf(r.top3Match()), r.top1Similarity(),
							"", "", "", withExtras(faqCells(r.top()), "", "", String.valueOf(r.ambiguous())));
				}
				for (PlanResultRow r : planResults) {
					String conditionOk = r.graded() ? String.valueOf(r.top1Ok()) : "";
					String acceptable = r.graded() ? csv(String.join(";", r.acceptable())) : "";
					writeCsvRow(writer, "PLAN", r.planCode(), r.aspect(), r.query(),
							String.valueOf(r.top1Match()), String.valueOf(r.top3Match()), r.top1Similarity(),
							"", "", "", withExtras(planCells(r.top()), conditionOk, acceptable, ""));
				}
				for (NegativeResultRow r : negativeResults) {
					String planName = r.planTop1() == null ? "" : r.planTop1().name();
					writeCsvRow(writer, "NEGATIVE", r.topic(), "-", r.query(), "false", "false",
							Math.max(r.faqTop1Similarity(), r.planTop1Similarity()),
							String.format("%.4f", r.faqTop1Similarity()),
							String.format("%.4f", r.planTop1Similarity()),
							planName, withExtras(faqCells(r.faqTop()), "", "", ""));
				}
			}
			log.info("CSV 리포트 저장: {}", file.toAbsolutePath());
		} catch (IOException exception) {
			log.warn("CSV 리포트 저장 실패 (요약 로그는 정상 출력됨): {}", exception.getMessage());
		}
	}

	/**
	 * 순위별 칸 뒤에 conditionTop1Ok, acceptablePlans, ambiguous 세 칸을 붙인다. conditionTop1Ok와 acceptablePlans는
	 * 요금제 행에서만, ambiguous는 FAQ 행에서만 채우고 나머지 행은 빈 칸이다.
	 */
	private List<String> withExtras(List<String> cells, String conditionOk, String acceptable, String ambiguous) {
		List<String> withExtras = new ArrayList<>(cells);
		withExtras.add(conditionOk);
		withExtras.add(acceptable);
		withExtras.add(ambiguous);
		return withExtras;
	}

	/** 상위 TOP_K개 FAQ 결과를 (유사도, 분류, 질문, 답변) 4칸씩으로 펼친다. 부족한 순위는 빈 칸. */
	private List<String> faqCells(List<FaqSimilarityResult> top) {
		List<String> cells = new ArrayList<>();
		for (int i = 0; i < TOP_K; i++) {
			if (i < top.size()) {
				FaqSimilarityResult r = top.get(i);
				cells.add(String.format("%.4f", r.similarity()));
				cells.add(csv(r.category() + "/" + r.subcategory()));
				cells.add(csv(r.question()));
				cells.add(csv(r.answer()));
			} else {
				cells.addAll(List.of("", "", "", ""));
			}
		}
		return cells;
	}

	/** 상위 TOP_K개 요금제 결과를 (유사도, 요금제 코드, 이름·월요금, 설명) 4칸씩으로 펼친다. */
	private List<String> planCells(List<PlanSimilarityResult> top) {
		List<String> cells = new ArrayList<>();
		for (int i = 0; i < TOP_K; i++) {
			if (i < top.size()) {
				PlanSimilarityResult r = top.get(i);
				cells.add(String.format("%.4f", r.similarity()));
				cells.add(csv(r.planCode()));
				cells.add(csv("%s (월 %,d원)".formatted(r.name(), r.monthlyFee())));
				cells.add(csv(r.description()));
			} else {
				cells.addAll(List.of("", "", "", ""));
			}
		}
		return cells;
	}

	private void writeCsvRow(Writer writer, String type, String label, String styleOrAspect, String query,
			String top1Match, String top3Match, double top1Similarity,
			String faqTop1Similarity, String planTop1Similarity, String planTop1Name,
			List<String> rankCells) throws IOException {
		List<String> cells = new ArrayList<>();
		cells.add(type);
		cells.add(csv(label));
		cells.add(styleOrAspect);
		cells.add(csv(query));
		cells.add(top1Match);
		cells.add(top3Match);
		cells.add(String.format("%.4f", top1Similarity));
		cells.add(faqTop1Similarity);
		cells.add(planTop1Similarity);
		cells.add(csv(planTop1Name));
		cells.addAll(rankCells);
		writer.write(String.join(",", cells) + "\n");
	}

	/** CSV 한 칸으로 안전하게 감싼다: 큰따옴표는 두 번, 줄바꿈은 공백으로 바꾼다. */
	private String csv(String value) {
		String flat = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
		return "\"" + flat.replace("\"", "\"\"") + "\"";
	}
}
