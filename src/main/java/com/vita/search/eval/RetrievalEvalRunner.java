package com.vita.search.eval;

import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.pipeline.FaqRetriever;
import com.vita.search.pipeline.FaqSelection;
import com.vita.search.pipeline.IdentityQueryTransformer;
import com.vita.search.pipeline.QueryTransformer;
import com.vita.search.pipeline.RetrievalOptions;
import com.vita.search.pipeline.RetrievalPipeline;
import com.vita.search.pipeline.RetrievalPipelineConfig;
import com.vita.search.pipeline.RetrievalResult;
import com.vita.search.pipeline.StageTimings;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.pipeline.TransformedQuery;
import com.vita.search.pipeline.VectorFaqRetriever;
import com.vita.search.regression.RegressionQueryReader;
import com.vita.search.service.FaqCandidateSelector;
import com.vita.search.service.IrrelevantQueryDetector;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 검색 평가셋(v2)의 질문마다 실제 검색 로직으로 후보를 뽑아 정답지와 비교하고, 검색 품질 지표를 낸다.
 *
 * <p>측정하는 것은 두 단계다.
 * <ol>
 *   <li>풀(후보 N개) 단계 — 유사도 상위 N개 안에 정답이 얼마나 들어왔는지(Recall, 정답 판정은 관련도 1점 이상).
 *       N은 {@code search.eval.pool-sizes}(기본 10,20,30,50)로 바꿔 가며 비교한다. 실제 검색은 topK와 상관없이 기본 30개를 가져온다.</li>
 *   <li>상위 K개 단계 — 풀에 분류 이름 가산점 재정렬과 답변 중복 제거를 적용해 뽑은 상위 K개(기본 10, {@code search.eval.top-k})가 정답인지
 *       (최종 Recall@K, Precision@K, nDCG@K, MRR, Hit@1/@K, 정답 판정은 관련도 2점 이상; 최종 Recall@K는 정답 답변 묶음 단위로 세고 1점 이상·2점 이상 둘 다 낸다). 실제 검색 로직({@code RetrievalPipeline})의
 *       후처리를 그대로 쓰고, threshold는 마지막에 따로 적용해 "threshold 통과 후" 지표를 함께 낸다.</li>
 * </ol>
 *
 * <p>검색은 서비스와 같은 {@link RetrievalPipeline}을 부른다(검색 흐름을 이 클래스가 따로 흉내 내지 않는다). 실험 변형은
 * {@code search.eval.query-transformer}와 {@code search.eval.faq-retriever}에 질문 변환기·FAQ 검색기의 빈 이름을 적어 끼운다
 * (기본은 변환 없음 + 한국어 벡터 검색 = Baseline). 지표 계산이 끝나면 서비스와 같은 설정으로 단계별 응답 시간(평균, p95)도 잰다.
 *
 * <p>질문 수를 50/100/150/200개로 잘라 지표가 얼마나 안정적인지도 출력한다. 로컬 도커(DB+임베딩 서버)가 떠 있고
 * 평가셋의 정답 FAQ가 DB에 적재돼 있을 때 {@code search.eval.enabled=true}로 켜서 수동 실행한다(기본은 꺼짐).
 * 결과는 {@code build/regression-report/retrieval-eval-*.csv}로도 저장된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "search.eval", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class RetrievalEvalRunner implements CommandLineRunner {

	/** 풀 단계 Recall에서 정답으로 보는 최소 관련도. */
	private static final int POOL_MIN_GRADE = 1;

	/** 상위 K개 단계 지표에서 정답으로 보는 최소 관련도. */
	private static final int TOP_MIN_GRADE = 2;

	/** 부트스트랩 반복에 쓰는 고정 시드(실행마다 같은 값이 나오게 한다). */
	private static final long BOOTSTRAP_SEED = 20261006L;

	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

	private final RetrievalPipeline servicePipeline;
	private final Map<String, QueryTransformer> queryTransformers;
	private final Map<String, FaqRetriever> faqRetrievers;
	private final RegressionQueryReader queryReader;
	private final ResourceLoader resourceLoader;
	private final JdbcTemplate jdbcTemplate;

	/** 평가셋 위치. classpath: 또는 file: 형식. */
	@Value("${search.eval.resource:classpath:data/regression/eval_v2/retrieval_eval_v2.jsonl}")
	private String resourceLocation;

	/** 상위 몇 개를 최종 결과로 볼지. 실제 검색(BE4 호출)의 topK와 같게 둔다. */
	@Value("${search.eval.top-k:10}")
	private int topK;

	/** 비교할 풀 크기 목록. */
	@Value("${search.eval.pool-sizes:10,20,30,50}")
	private List<Integer> poolSizes;

	/** 질문 수 안정성 분석과 약한 질문 목록에 쓰는 대표 풀 크기. 실제 검색의 풀 크기(30)와 같게 둔다. */
	@Value("${search.eval.main-pool-size:30}")
	private int mainPoolSize;

	/** 질문을 앞에서부터 몇 개씩 잘라 안정성을 볼지. */
	@Value("${search.eval.subset-sizes:50,100,150,200}")
	private List<Integer> subsetSizes;

	/** 안정성 분석에서 무작위 뽑기를 반복할 횟수. */
	@Value("${search.eval.bootstrap-draws:1000}")
	private int bootstrapDraws;

	/** 평가에 쓸 질문 변환기의 빈 이름. 기본은 변환 없음(Baseline). 영어 번역·질문 재작성 실험은 그 변환기 빈 이름을 적는다. */
	@Value("${search.eval.query-transformer:" + IdentityQueryTransformer.BEAN_NAME + "}")
	private String queryTransformerName;

	/** 평가에 쓸 FAQ 후보 검색기의 빈 이름. 기본은 한국어 벡터 검색(Baseline). 영어 컬럼·Hybrid 실험은 그 검색기 빈 이름을 적는다. */
	@Value("${search.eval.faq-retriever:" + VectorFaqRetriever.BEAN_NAME + "}")
	private String faqRetrieverName;

	/**
	 * 질문 변환 결과를 저장·재사용하는 파일(JSONL). 비어 있으면 쓰지 않는다. LLM 변환은 실행마다 결과가 달라질 수 있어서, 변환 결과를
	 * 파일에 남겨 두면 같은 변환 결과로 다시 평가(예: 변환 위에 Hybrid를 얹은 C+H)할 수 있고 변환 호출도 아낀다.
	 * 파일 하나는 하나의 실험(변환기·프롬프트 버전) 전용으로 쓴다. 단계별 응답 시간 측정은 캐시를 쓰지 않고 실제 변환기를 부른다.
	 */
	@Value("${search.eval.transform-cache:}")
	private String transformCachePath;

	/** 단계별 응답 시간을 질문 하나당 몇 번 반복해서 잴지. 0이면 시간 측정을 건너뛴다. */
	@Value("${search.eval.timing-repeats:3}")
	private int timingRepeats;

	/** 시간 측정 전에 결과를 버리고 먼저 돌려 둘 횟수(서버·DB 연결 예열). 첫 요청은 느려서 평균을 왜곡한다. */
	@Value("${search.eval.timing-warmup:5}")
	private int timingWarmup;

	/** 질문 하나를 풀 크기 하나로 평가한 결과. */
	private record EvalRow(RetrievalEvalQuestion question, int poolSize, int relevantCount,
			double recallPool1, double recallPool2, double recallTop1, double recallTop2, int distinctInPool, int maxDuplicates,
			List<FaqSimilarityResult> top, List<String> topIds, Map<String, Integer> grades,
			double precisionK, double ndcgK, double mrr, double hit1, double hitK,
			int passedThreshold, double e2ePrecisionK, double e2eHitK, boolean ruleBlocked,
			TransformedQuery transformed) {

		double top1Similarity() {
			return top.isEmpty() ? 0.0 : top.get(0).similarity();
		}
	}

	@Override
	public void run(String... args) {
		List<RetrievalEvalQuestion> questions = queryReader.read(
				resourceLoader.getResource(resourceLocation), RetrievalEvalQuestion.class);
		Map<Long, String> sourceIds = loadSourceIds();
		verifyGroundTruthLoaded(questions, new HashSet<>(sourceIds.values()));

		// 서비스 파이프라인과 같은 설정(threshold, 가산점)에서 질문 변환기와 FAQ 검색기만 갈아끼운 평가용 파이프라인.
		QueryTransformer rawTransformer = RetrievalPipelineConfig.pick(queryTransformers, queryTransformerName, "search.eval.query-transformer");
		FaqRetriever retriever = RetrievalPipelineConfig.pick(faqRetrievers, faqRetrieverName, "search.eval.faq-retriever");
		// 점수 계산용 파이프라인은 변환 결과 저장 파일이 지정되면 저장된 결과를 다시 쓴다. 단계별 시간 측정용은 항상 실제 변환기를 쓴다
		// (저장된 결과를 쓰면 변환 시간이 0에 가깝게 나온다).
		CachedQueryTransformer cachedTransformer = transformCachePath.isBlank()
				? null : new CachedQueryTransformer(rawTransformer, Path.of(transformCachePath));
		RetrievalPipeline pipeline = servicePipeline.with(cachedTransformer != null ? cachedTransformer : rawTransformer, retriever);
		RetrievalPipeline timingPipeline = servicePipeline.with(rawTransformer, retriever);
		double similarityThreshold = pipeline.settings().faqThreshold();
		if (cachedTransformer != null) {
			log.info("질문 변환 결과 저장 파일 사용: {} (저장된 변환 {}건)", transformCachePath, cachedTransformer.size());
		}

		List<Integer> sizes = effectivePoolSizes();
		int maxPoolSize = sizes.get(sizes.size() - 1);
		log.info("평가셋 {}문항, 풀 크기 {}, 상위 {}개, threshold {}, 가산점 {}, 질문 변환기 {}, 후보 검색기 {}", questions.size(), sizes, topK,
				similarityThreshold, pipeline.settings().categoryBoostBonus(), queryTransformerName, faqRetrieverName);

		List<EvalRow> rows = new ArrayList<>();
		for (RetrievalEvalQuestion question : questions) {
			// 질문 변환·임베딩·후보 검색은 질문마다 가장 큰 풀로 한 번만 한다. 후보는 순위 순이라 풀 N개는 그 앞 N개와 같다.
			RetrievalResult retrieved = pipeline.run(question.query(), RetrievalOptions.forEval(topK, maxPoolSize, false));
			List<FaqSimilarityResult> fullPool = retrieved.faq().pool();
			boolean ruleBlocked = !IrrelevantQueryDetector.detect(question.query()).isEmpty();
			for (int size : sizes) {
				List<FaqSimilarityResult> pool = fullPool.subList(0, Math.min(size, fullPool.size()));
				rows.add(evaluate(question, pipeline, pool, size, sourceIds, ruleBlocked, retrieved.query()));
			}
		}

		if (cachedTransformer != null) {
			cachedTransformer.save();
			log.info("질문 변환 결과 저장 파일: 저장된 결과 사용 {}건, 새로 변환 {}건, 일시 실패로 저장하지 않음 {}건 → 파일 {}건",
					cachedTransformer.hits(), cachedTransformer.misses(), cachedTransformer.skippedTransient(), cachedTransformer.size());
		}

		List<EvalRow> mainRows = rows.stream()
				.filter(row -> row.poolSize() == mainPoolSize)
				.sorted(Comparator.comparingInt(row -> row.question().subsetOrder()))
				.toList();

		printPoolSizeSummary(rows, sizes, similarityThreshold);
		printStyleSummary(mainRows);
		printFallbackSummary(mainRows);
		printSubsetStability(mainRows);
		printWeakQuestions(mainRows);
		writeCsvReport(rows);
		measureStageTimings(timingPipeline, questions);
		log.info("RETRIEVAL EVAL DONE");
	}

	private List<Integer> effectivePoolSizes() {
		TreeSet<Integer> sizes = new TreeSet<>(poolSizes);
		sizes.add(mainPoolSize);
		return List.copyOf(sizes);
	}

	/** DB의 FAQ id → 평가셋이 쓰는 원본 ID(source_faq_id). */
	private Map<Long, String> loadSourceIds() {
		Map<Long, String> map = new HashMap<>();
		jdbcTemplate.query("SELECT id, source_faq_id FROM faqs WHERE status = 'ACTIVE' AND source_faq_id IS NOT NULL",
				resultSet -> {
					map.put(resultSet.getLong("id"), resultSet.getString("source_faq_id"));
				});
		return map;
	}

	/**
	 * 평가셋의 정답 FAQ가 모두 DB에 적재돼 있는지 확인한다. 데이터가 다르면(예: 옛 FAQ가 남아 있음) 모든 지표가
	 * 의미가 없어지므로, 조용히 낮은 점수를 내는 대신 바로 중단한다.
	 */
	private void verifyGroundTruthLoaded(List<RetrievalEvalQuestion> questions, Set<String> loadedIds) {
		Set<String> missing = new TreeSet<>();
		for (RetrievalEvalQuestion question : questions) {
			for (String faqId : question.relevantIds(POOL_MIN_GRADE)) {
				if (!loadedIds.contains(faqId)) {
					missing.add(faqId);
				}
			}
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException("평가셋의 정답 FAQ " + missing.size() + "개가 DB에 없습니다(예: "
					+ missing.stream().limit(3).toList() + "). FAQ 데이터 적재 상태(source_faq_id)를 확인하세요.");
		}
	}

	private EvalRow evaluate(RetrievalEvalQuestion question, RetrievalPipeline pipeline, List<FaqSimilarityResult> pool,
			int poolSize, Map<Long, String> sourceIds, boolean ruleBlocked, TransformedQuery transformed) {
		List<String> poolIds = pool.stream().map(result -> idOf(result, sourceIds)).toList();

		Map<String, Integer> grades = question.gradeById();
		double recallPool1 = RetrievalMetrics.recall(question.relevantIds(POOL_MIN_GRADE), poolIds);
		double recallPool2 = RetrievalMetrics.recall(question.relevantIds(TOP_MIN_GRADE), poolIds);

		// 풀 안에서 서로 다른 답변이 몇 개나 되는지, 같은 답변이 최대 몇 번 반복되는지(중복 문제 확인용).
		Map<List<String>, Integer> answerCounts = new LinkedHashMap<>();
		for (FaqSimilarityResult result : pool) {
			answerCounts.merge(Arrays.asList(result.category(), result.subcategory(), result.answer()), 1, Integer::sum);
		}
		int maxDuplicates = answerCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);

		// 서비스와 같은 후처리: 분류 이름 가산점 재정렬 → 답변 중복 제거 → 상위 topK → threshold.
		FaqSelection selection = pipeline.selectFaq(question.query(), pool, topK);
		List<FaqSimilarityResult> top = selection.candidates();
		List<String> topIds = top.stream().map(result -> idOf(result, sourceIds)).toList();
		List<String> passedIds = selection.results().stream().map(result -> idOf(result, sourceIds)).toList();

		return new EvalRow(question, poolSize, question.relevantIds(POOL_MIN_GRADE).size(), recallPool1, recallPool2,
				RetrievalMetrics.groupRecall(question.relevantGroups(POOL_MIN_GRADE), topIds),
				RetrievalMetrics.groupRecall(question.relevantGroups(TOP_MIN_GRADE), topIds),
				answerCounts.size(), maxDuplicates, top, topIds, grades,
				RetrievalMetrics.precisionAtK(topIds, grades, topK, TOP_MIN_GRADE),
				RetrievalMetrics.ndcgAtK(topIds, grades, question.groupGradesDescending(), topK),
				RetrievalMetrics.reciprocalRank(topIds, grades, TOP_MIN_GRADE),
				RetrievalMetrics.hitAtK(topIds, grades, 1, TOP_MIN_GRADE),
				RetrievalMetrics.hitAtK(topIds, grades, topK, TOP_MIN_GRADE),
				passedIds.size(),
				RetrievalMetrics.precisionAtK(passedIds, grades, topK, TOP_MIN_GRADE),
				RetrievalMetrics.hitAtK(passedIds, grades, topK, TOP_MIN_GRADE),
				ruleBlocked, transformed);
	}

	private String idOf(FaqSimilarityResult result, Map<Long, String> sourceIds) {
		return sourceIds.getOrDefault(result.id(), "ID-" + result.id());
	}

	// ---- 출력 ----

	/** 풀 크기별 요약. 후보 풀 크기(10/20/30/50) 비교 근거로 쓴다. */
	private void printPoolSizeSummary(List<EvalRow> rows, List<Integer> sizes, double similarityThreshold) {
		log.info("===== 풀 크기별 지표 (질문 {}개) =====", rows.stream().filter(r -> r.poolSize() == sizes.get(0)).count());
		log.info("Recall은 관련도 1점 이상, 상위 {}개 지표는 관련도 2점 이상을 정답으로 본다. 'threshold 후'는 유사도 {} 이상만 남긴 결과. 풀 Recall은 FAQ ID 기준, 최종 Recall@{}는 정답 답변 묶음 기준(원문·변형 중 하나만 가져와도 찾은 것).",
				topK, similarityThreshold, topK);
		for (int size : sizes) {
			List<EvalRow> group = rows.stream().filter(r -> r.poolSize() == size).toList();
			long fewer = group.stream().filter(r -> r.distinctInPool() < topK).count();
			log.info("풀 {}: Recall(1점↑) {} | Recall(2점↑) {} | 최종 Recall@{}(1점↑) {} | 최종 Recall@{}(2점↑) {} | P@{} {} | nDCG@{} {} | MRR {} | Hit@1 {} | Hit@{} {}",
					size, pct(avg(group, EvalRow::recallPool1)), pct(avg(group, EvalRow::recallPool2)),
					topK, pct(avg(group, EvalRow::recallTop1)), topK, pct(avg(group, EvalRow::recallTop2)),
					topK, pct(avg(group, EvalRow::precisionK)), topK, pct(avg(group, EvalRow::ndcgK)), pct(avg(group, EvalRow::mrr)),
					pct(avg(group, EvalRow::hit1)), topK, pct(avg(group, EvalRow::hitK)));
			log.info("      풀 안 서로 다른 답변 평균 {}개(최소 {}), 같은 답변 최대 반복 {}회, 서로 다른 답변이 {}개 미만인 질문 {}개 | "
							+ "threshold 후: 남은 결과 평균 {}개, P@{} {}, Hit@{} {}, 규칙으로 막힌 질문 {}개",
					fmt(avg(group, r -> r.distinctInPool())), group.stream().mapToInt(EvalRow::distinctInPool).min().orElse(0),
					group.stream().mapToInt(EvalRow::maxDuplicates).max().orElse(0), topK, fewer,
					fmt(avg(group, r -> r.passedThreshold())), topK, pct(avg(group, EvalRow::e2ePrecisionK)),
					topK, pct(avg(group, EvalRow::e2eHitK)), group.stream().filter(EvalRow::ruleBlocked).count());
		}
	}

	private void printStyleSummary(List<EvalRow> mainRows) {
		log.info("===== 말투별 지표 (풀 {}) =====", mainPoolSize);
		Map<String, List<EvalRow>> byStyle = new LinkedHashMap<>();
		for (EvalRow row : mainRows) {
			byStyle.computeIfAbsent(row.question().style(), key -> new ArrayList<>()).add(row);
		}
		byStyle.forEach((style, group) -> log.info("{} ({}개): 풀 Recall {} | Recall@{} {} | P@{} {} | nDCG@{} {} | MRR {} | Hit@{} {}",
				style, group.size(), pct(avg(group, EvalRow::recallPool1)), topK, pct(avg(group, EvalRow::recallTop1)), topK, pct(avg(group, EvalRow::precisionK)),
				topK, pct(avg(group, EvalRow::ndcgK)), pct(avg(group, EvalRow::mrr)), topK, pct(avg(group, EvalRow::hitK))));
	}

	/**
	 * 폴백 현황. 질문이 원문으로 되돌아간 경우(질문 변환 폴백)와 "관련 정보 없음"이 된 경우를 센다.
	 *
	 * <p>질문 변환기가 질문을 바꾸지 않는 구현(Baseline)이면 변환 폴백은 집계 대상이 아니라 "관련 FAQ 없음"과 규칙 차단만 낸다.
	 * 변환기를 쓰면 변환 결과의 종류와 폴백 사유별 건수, 그리고 FAQ 검색에 변환된 질문이 쓰인 질문과 원문이 쓰인 질문의 점수를
	 * 따로 낸다. 폴백으로 원문이 쓰인 질문이 섞여 있으면 "질문 변환 효과"가 실제보다 Baseline에 가까워지므로, 두 집단을 나눠 봐야 한다.
	 */
	private void printFallbackSummary(List<EvalRow> mainRows) {
		log.info("===== 폴백 현황 (풀 {}, 질문 {}개) =====", mainPoolSize, mainRows.size());
		long noFaq = mainRows.stream().filter(row -> row.passedThreshold() == 0).count();
		long blocked = mainRows.stream().filter(EvalRow::ruleBlocked).count();
		log.info("관련 FAQ 없음(threshold 후 결과 0개): {}개({}) | 무관 질문 규칙으로 막힌 질문: {}개({})",
				noFaq, rate(noFaq, mainRows.size()), blocked, rate(blocked, mainRows.size()));

		Map<TransformInfo.Kind, Long> byKind = new EnumMap<>(TransformInfo.Kind.class);
		for (EvalRow row : mainRows) {
			byKind.merge(row.transformed().info().kind(), 1L, Long::sum);
		}
		if (byKind.keySet().equals(Set.of(TransformInfo.Kind.IDENTITY))) {
			log.info("질문 변환 없음(변환기 {}): 변환 폴백은 집계 대상이 아니다", queryTransformerName);
			return;
		}

		log.info("질문 변환 결과: 변환됨(CHANGED) {}개 | 한쪽만 변환(PARTIAL_NULL) {}개 | 원문으로 폴백(FALLBACK_ORIGINAL) {}개 | 변환 안 함(IDENTITY) {}개",
				byKind.getOrDefault(TransformInfo.Kind.CHANGED, 0L), byKind.getOrDefault(TransformInfo.Kind.PARTIAL_NULL, 0L),
				byKind.getOrDefault(TransformInfo.Kind.FALLBACK_ORIGINAL, 0L), byKind.getOrDefault(TransformInfo.Kind.IDENTITY, 0L));

		Map<String, Long> reasons = new TreeMap<>();
		mainRows.stream().map(row -> row.transformed().info())
				.filter(info -> info.kind() == TransformInfo.Kind.FALLBACK_ORIGINAL)
				.forEach(info -> reasons.merge(info.reason(), 1L, Long::sum));
		log.info("원문 폴백 사유: {}", reasons.isEmpty() ? "없음" : reasons);
		// 사유별 건수를 비율로도 낸다(질문 수가 달라도 실험끼리 비교할 수 있게).
		StringBuilder reasonRates = new StringBuilder();
		reasons.forEach((reason, count) -> reasonRates.append(reasonRates.length() == 0 ? "" : " | ")
				.append(reason).append(' ').append(rate(count, mainRows.size())));
		if (reasonRates.length() > 0) {
			log.info("원문 폴백 사유별 비율: {}", reasonRates);
		}

		long faqNull = mainRows.stream().filter(row -> row.transformed().info().faqNull()).count();
		long planNull = mainRows.stream().filter(row -> row.transformed().info().planNull()).count();
		long sameText = mainRows.stream()
				.filter(this::faqQueryTransformed)
				.filter(row -> row.transformed().faqQuery().equals(row.transformed().original()))
				.count();
		log.info("한쪽만 비어 있음: FAQ용 {}개(FAQ 검색은 원문으로 진행), 요금제용 {}개 | 변환됐지만 FAQ용 질문이 원문과 같은 질문: {}개",
				faqNull, planNull, sameText);

		long faqOriginalCount = mainRows.stream().filter(row -> !faqQueryTransformed(row)).count();
		long fallbackCount = byKind.getOrDefault(TransformInfo.Kind.FALLBACK_ORIGINAL, 0L);
		log.info("FAQ 변환 적용률 {} | FAQ 검색에 원문을 쓴 비율 {} (원문 폴백 {} + FAQ용만 비어 있음 {}) | 요금제용만 비어 있음 {} (FAQ 질문 평가에서는 정상)",
				rate(mainRows.size() - faqOriginalCount, mainRows.size()), rate(faqOriginalCount, mainRows.size()),
				rate(fallbackCount, mainRows.size()), rate(faqOriginalCount - fallbackCount, mainRows.size()),
				rate(planNull, mainRows.size()));

		List<EvalRow> faqTransformed = mainRows.stream().filter(this::faqQueryTransformed).toList();
		List<EvalRow> faqOriginal = mainRows.stream().filter(row -> !faqQueryTransformed(row)).toList();
		printGroupScores("FAQ 검색에 변환된 질문을 쓴 질문", faqTransformed);
		printGroupScores("FAQ 검색에 원문을 쓴 질문(폴백·FAQ용 비어 있음)", faqOriginal);
	}

	/** FAQ 검색에 변환된 질문이 실제로 쓰였는지. 원문으로 폴백했거나 FAQ용 질문이 비어 있으면(원문으로 채움) false. */
	private boolean faqQueryTransformed(EvalRow row) {
		TransformInfo info = row.transformed().info();
		return info.applied() && !info.faqNull();
	}

	private void printGroupScores(String label, List<EvalRow> group) {
		if (group.isEmpty()) {
			log.info("{}: 0개", label);
			return;
		}
		log.info("{} ({}개): 최종 Recall@{}(1점↑) {} | Recall@{}(2점↑) {} | nDCG@{} {} | MRR {} | Hit@1 {} | Hit@{} {} | threshold 후 남은 결과 평균 {}개",
				label, group.size(), topK, pct(avg(group, EvalRow::recallTop1)), topK, pct(avg(group, EvalRow::recallTop2)),
				topK, pct(avg(group, EvalRow::ndcgK)), pct(avg(group, EvalRow::mrr)), pct(avg(group, EvalRow::hit1)),
				topK, pct(avg(group, EvalRow::hitK)), fmt(avg(group, row -> row.passedThreshold())));
	}

	/**
	 * 질문을 앞에서부터 N개로 자른 지표(평가셋의 subsetOrder 순서)와, 질문을 N개씩 무작위로 뽑는 일을 반복했을 때의
	 * 표준편차를 보여 준다. 값이 전체(마지막 행)와 거의 같고 표준편차가 충분히 작아지는 가장 작은 N이 안정적인 질문 수다.
	 */
	private void printSubsetStability(List<EvalRow> mainRows) {
		String[] names = {"Recall", "P@" + topK, "nDCG@" + topK, "MRR"};
		List<ToDoubleFunction<EvalRow>> getters = List.of(EvalRow::recallPool1, EvalRow::precisionK, EvalRow::ndcgK, EvalRow::mrr);
		double[][] values = new double[getters.size()][];
		for (int m = 0; m < getters.size(); m++) {
			values[m] = mainRows.stream().mapToDouble(getters.get(m)).toArray();
		}
		log.info("===== 질문 수별 안정성 (풀 {}, 부트스트랩 {}회) =====", mainPoolSize, bootstrapDraws);
		log.info("각 칸은 '앞 N개 평균 ±무작위 뽑기 표준편차'다. 전체 {}개 평균: {}", mainRows.size(),
				joinMeans(names, values));
		Integer stable = null;
		for (int size : subsetSizes) {
			if (size > mainRows.size()) {
				continue;
			}
			StringBuilder line = new StringBuilder("N=" + size + ": ");
			boolean ok = true;
			for (int m = 0; m < names.length; m++) {
				double mean = RetrievalMetrics.prefixMean(values[m], size);
				double std = RetrievalMetrics.bootstrapStdOfMean(values[m], size, bootstrapDraws, BOOTSTRAP_SEED + size);
				double gap = Math.abs(mean - RetrievalMetrics.mean(values[m]));
				ok &= std <= 0.03 && gap <= 0.02;
				line.append(names[m]).append(' ').append(pct(mean)).append(" ±").append(String.format(Locale.ROOT, "%.1f%%p", std * 100)).append(" | ");
			}
			log.info("{}{}", line, ok ? "← 기준 충족" : "");
			if (ok && stable == null) {
				stable = size;
			}
		}
		log.info("안정적인 가장 작은 질문 수(표준편차 3%p 이하, 전체 평균과 차이 2%p 이하): {}",
				stable == null ? "기준을 넘는 값이 없음" : stable + "개");
	}

	private String joinMeans(String[] names, double[][] values) {
		StringBuilder sb = new StringBuilder();
		for (int m = 0; m < names.length; m++) {
			sb.append(names[m]).append(' ').append(pct(RetrievalMetrics.mean(values[m]))).append("  ");
		}
		return sb.toString().trim();
	}

	/** 정답을 거의 못 가져온 질문(상위 K개에 정답이 없거나 풀에 정답이 절반도 안 들어옴)을 나열한다. */
	private void printWeakQuestions(List<EvalRow> mainRows) {
		List<EvalRow> weak = mainRows.stream()
				.filter(row -> row.hitK() == 0.0 || row.recallPool1() < 0.5)
				.sorted(Comparator.comparingDouble(EvalRow::recallPool1))
				.toList();
		log.info("===== 약한 질문 (상위 {}개에 정답 없음 또는 풀 Recall 50% 미만): {}개 =====", topK, weak.size());
		weak.stream().limit(20).forEach(row -> log.info("{} [{}] Recall {} Hit@{} {} | {} | 1위: {}",
				row.question().qid(), row.question().style(), pct(row.recallPool1()), topK, pct(row.hitK()),
				row.question().query(), row.top().isEmpty() ? "-" : row.top().get(0).question()));
	}

	// ---- 단계별 응답 시간 ----

	/**
	 * 서비스와 같은 설정(후보 풀 {@link FaqCandidateSelector#poolSize}, 요금제 검색 포함)으로 질문마다 검색을 {@code timingRepeats}번
	 * 반복하며 단계별 소요 시간을 잰다. 처음 {@code timingWarmup}번은 결과를 버린다(첫 요청은 서버·연결 준비로 느려서 평균을 왜곡한다).
	 * 평가셋 질문은 FAQ 질문이라, 요금제 검색 시간은 "FAQ 질문에 대해 요금제 검색을 한 시간"이다.
	 */
	private void measureStageTimings(RetrievalPipeline pipeline, List<RetrievalEvalQuestion> questions) {
		if (timingRepeats <= 0 || questions.isEmpty()) {
			return;
		}
		RetrievalOptions options = RetrievalOptions.forEval(topK, FaqCandidateSelector.poolSize(topK), true);
		for (int i = 0; i < timingWarmup; i++) {
			pipeline.run(questions.get(i % questions.size()).query(), options);
		}

		StageTimings.Stage[] stages = StageTimings.Stage.values();
		int samples = questions.size() * timingRepeats;
		// 단계별 소요 시간(ms) 표본. 마지막 칸은 전체 시간이다.
		double[][] perStage = new double[stages.length + 1][samples];
		int index = 0;
		for (int repeat = 0; repeat < timingRepeats; repeat++) {
			for (RetrievalEvalQuestion question : questions) {
				StageTimings timings = pipeline.run(question.query(), options).timings();
				for (int s = 0; s < stages.length; s++) {
					perStage[s][index] = StageTimings.toMillis(timings.nanosOf(stages[s]));
				}
				perStage[stages.length][index] = StageTimings.toMillis(timings.totalNanos());
				index++;
			}
		}

		log.info("===== 단계별 응답 시간 (서비스와 같은 설정: 풀 {}, 상위 {}개, 요금제 검색 포함 / 질문 {}개 x {}회, 예열 {}회 제외) =====",
				options.poolSize(), topK, questions.size(), timingRepeats, timingWarmup);
		List<String> csvLines = new ArrayList<>();
		for (int s = 0; s <= stages.length; s++) {
			String label = s < stages.length ? stages[s].label() : "전체";
			double mean = RetrievalMetrics.mean(perStage[s]);
			double p95 = RetrievalMetrics.percentile(perStage[s], 0.95);
			log.info("{}: 평균 {} ms, p95 {} ms, 측정 {}건", label, fmt(mean), fmt(p95), samples);
			csvLines.add(label + "," + num(mean) + "," + num(p95) + "," + samples);
		}
		writeTimingCsv(csvLines);
	}

	private void writeTimingCsv(List<String> lines) {
		Path dir = Path.of("build", "regression-report");
		Path file = dir.resolve("retrieval-eval-timing-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".csv");
		try {
			Files.createDirectories(dir);
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				// 엑셀이 한글을 깨뜨리지 않도록 UTF-8 BOM을 붙인다.
				writer.write('\uFEFF');
				writer.write("stage,mean_ms,p95_ms,samples\n");
				for (String line : lines) {
					writer.write(line + "\n");
				}
			}
			log.info("단계별 응답 시간 리포트 저장: {}", file.toAbsolutePath());
		} catch (IOException exception) {
			log.warn("단계별 응답 시간 리포트 저장 실패: {}", exception.getMessage());
		}
	}

	// ---- CSV ----

	private void writeCsvReport(List<EvalRow> rows) {
		Path dir = Path.of("build", "regression-report");
		Path file = dir.resolve("retrieval-eval-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".csv");
		try {
			Files.createDirectories(dir);
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				// 엑셀이 한글을 깨뜨리지 않도록 UTF-8 BOM을 붙인다.
				writer.write('﻿');
				writer.write("qid,subsetOrder,style,query,poolSize,relevantCount,recallPool1,recallPool2,recallTop1,recallTop2,distinctInPool,maxDuplicates,"
						+ "precisionK,ndcgK,mrr,hit1,hitK,passedThreshold,e2ePrecisionK,e2eHitK,ruleBlocked,"
						+ topColumnNames() + ",transformKind,fallbackReason,faqNull,planNull,faqQuery,planQuery\n");
				for (EvalRow row : rows) {
					writeRow(writer, row);
				}
			}
			log.info("검색 평가 리포트 저장: {}", file.toAbsolutePath());
		} catch (IOException exception) {
			log.warn("검색 평가 리포트 저장 실패: {}", exception.getMessage());
		}
	}

	/** 상위 K개 각각의 CSV 열 이름(top1_id,top1_grade,top1_sim,top1_question,top2_id,...). */
	private String topColumnNames() {
		StringBuilder sb = new StringBuilder();
		for (int i = 1; i <= topK; i++) {
			if (i > 1) {
				sb.append(',');
			}
			sb.append("top").append(i).append("_id,top").append(i).append("_grade,top").append(i)
					.append("_sim,top").append(i).append("_question");
		}
		return sb.toString();
	}

	private void writeRow(Writer writer, EvalRow row) throws IOException {
		RetrievalEvalQuestion q = row.question();
		StringBuilder sb = new StringBuilder();
		sb.append(q.qid()).append(',').append(q.subsetOrder()).append(',').append(q.style()).append(',')
				.append(csv(q.query())).append(',').append(row.poolSize()).append(',').append(row.relevantCount()).append(',')
				.append(num(row.recallPool1())).append(',').append(num(row.recallPool2())).append(',')
				.append(num(row.recallTop1())).append(',').append(num(row.recallTop2())).append(',')
				.append(row.distinctInPool()).append(',').append(row.maxDuplicates()).append(',')
				.append(num(row.precisionK())).append(',')
				.append(num(row.ndcgK())).append(',').append(num(row.mrr())).append(',').append(num(row.hit1())).append(',')
				.append(num(row.hitK())).append(',').append(row.passedThreshold()).append(',')
				.append(num(row.e2ePrecisionK())).append(',').append(num(row.e2eHitK())).append(',')
				.append(row.ruleBlocked() ? "Y" : "N");
		for (int i = 0; i < topK; i++) {
			if (i < row.top().size()) {
				FaqSimilarityResult result = row.top().get(i);
				String id = row.topIds().get(i);
				sb.append(',').append(id).append(',').append(row.grades().getOrDefault(id, 0)).append(',')
						.append(num(result.similarity())).append(',').append(csv(result.question()));
			} else {
				sb.append(",,,,");
			}
		}
		TransformedQuery transformed = row.transformed();
		TransformInfo info = transformed.info();
		sb.append(',').append(info.kind().name()).append(',').append(info.reason() == null ? "" : info.reason())
				.append(',').append(info.faqNull() ? "Y" : "N").append(',').append(info.planNull() ? "Y" : "N")
				.append(',').append(csv(oneLine(transformed.faqQuery()))).append(',').append(csv(oneLine(transformed.planQuery())));
		sb.append('\n');
		writer.write(sb.toString());
	}

	/** 줄바꿈을 글자 그대로 "\n"로 바꿔 CSV 한 줄이 깨지지 않게 한다. */
	private static String oneLine(String value) {
		return value == null ? "" : value.replace("\r", "").replace("\n", "\\n");
	}

	private static String csv(String value) {
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	private static String num(double value) {
		return String.format(Locale.ROOT, "%.4f", value);
	}

	// ---- 계산 보조 ----

	private static double avg(List<EvalRow> rows, ToDoubleFunction<EvalRow> getter) {
		return rows.stream().mapToDouble(getter).average().orElse(0.0);
	}

	/** 건수를 전체 대비 비율 문자열로. 전체가 0이면 0.0%. */
	private static String rate(long count, long total) {
		return pct(total == 0 ? 0.0 : (double) count / total);
	}

	private static String pct(double value) {
		return String.format(Locale.ROOT, "%.1f%%", value * 100);
	}

	private static String fmt(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
