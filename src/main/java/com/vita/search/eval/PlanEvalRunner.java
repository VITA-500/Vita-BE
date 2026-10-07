package com.vita.search.eval;

import com.vita.search.dto.PlanReference;
import com.vita.search.pipeline.FaqRetriever;
import com.vita.search.pipeline.IdentityQueryTransformer;
import com.vita.search.pipeline.QueryTransformer;
import com.vita.search.pipeline.RetrievalOptions;
import com.vita.search.pipeline.RetrievalPipeline;
import com.vita.search.pipeline.RetrievalPipelineConfig;
import com.vita.search.pipeline.RetrievalResult;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.pipeline.TransformedQuery;
import com.vita.search.pipeline.VectorFaqRetriever;
import com.vita.search.regression.RegressionQueryReader;
import com.vita.search.service.FaqCandidateSelector;
import com.vita.search.service.PlanLookupService;
import com.vita.search.service.PlanSortKey;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * 요금제 평가셋(plan_eval_v1)의 질문마다 실제 검색 경로로 요금제를 찾고, BE4에 전달되는 요금제 목록을 정답지와 비교해
 * 요금제 검색 품질을 낸다. FAQ 평가({@link RetrievalEvalRunner})의 요금제 버전이다.
 *
 * <p>무엇을 재는가: 서비스가 BE4에 돌려주는 최종 요금제 목록({@code planReferences}: 조건 매칭, 유사도 threshold,
 * 무관 질문 규칙까지 적용된 결과)이다. 질문 유형별로 판정이 다르다.
 * <ul>
 *   <li>일반 유형(이름·금액·데이터량·무제한·대상·통화문자·조합·서술형) — 정답 포함률(Recall), 정밀도, 정확 일치율, Hit@1, MRR, nDCG</li>
 *   <li>최상급(가장 저렴한 등) — BE4가 의도(정렬 기준)를 알아낸 뒤 부르는 정형 조회 경로({@link PlanLookupService})로 평가한다.
 *       의도 분류는 BE4(LLM)가 하므로 이 평가에는 포함되지 않는다.</li>
 *   <li>요금제 질문 아님 — 요금제가 하나라도 전달되면 누수</li>
 *   <li>정확히 맞는 요금제 없음 — 안내할 대안이 전달되는지</li>
 * </ul>
 *
 * <p>요금제 개수(조건 매칭이 안 될 때 돌려줄 요금제 수)는 {@code search.plan-eval.top-ks}(기본 3,10)로 여러 값을 한 번에 잰다.
 * 요금제 개수는 FAQ의 topK와 따로 설정되므로(plan.retrieval.top-k), 이 값으로 개수별 점수를 비교해 기본값을 정한다.
 * 질문 변환기·검색기는 {@code search.plan-eval.query-transformer}, {@code search.plan-eval.faq-retriever}로 갈아끼울 수 있다
 * (질문 변환의 요금제용 질문 {@code planQuery} 효과를 재는 용도). 변환 결과는 {@code search.plan-eval.transform-cache} 파일에 저장해
 * 같은 변환 결과로 다시 평가할 수 있고, 변환이 폴백(원문으로 돌아감)된 질문 수와 변환된 질문·원문 질문의 점수는 "질문 변환 현황"으로 따로 낸다.
 * 로컬 도커(DB+임베딩 서버)가 떠 있고 요금제 15종이 DB에 있을 때
 * {@code search.plan-eval.enabled=true}로 켜서 수동 실행하며, 결과는 {@code build/regression-report/plan-eval-*.csv}로도 저장된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "search.plan-eval", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PlanEvalRunner implements CommandLineRunner {

	/** 판정이 다른 유형: 요금제가 하나도 전달되지 않아야 한다. */
	private static final String TYPE_NONE = "NONE";

	/** 판정이 다른 유형: 정답이 없고 안내할 대안만 있다. */
	private static final String TYPE_NO_EXACT = "NO_EXACT";

	/** 최상급 유형: BE4가 정렬 기준을 알아낸 뒤 정형 조회로 처리한다. */
	private static final String TYPE_EXTREME = "EXTREME";

	/** 요금제 평가에서 고정해 두는 FAQ의 topK. 요금제 점수에는 영향이 없다. */
	private static final int FAQ_TOP_K = 3;

	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

	private final RetrievalPipeline servicePipeline;
	private final Map<String, QueryTransformer> queryTransformers;
	private final Map<String, FaqRetriever> faqRetrievers;
	private final PlanLookupService planLookupService;
	private final RegressionQueryReader queryReader;
	private final ResourceLoader resourceLoader;
	private final JdbcTemplate jdbcTemplate;

	/** 요금제 평가셋 위치. classpath: 또는 file: 형식. */
	@Value("${search.plan-eval.resource:classpath:data/regression/eval_v2/plan_eval_v1.jsonl}")
	private String resourceLocation;

	/** 비교할 요금제 개수 목록(조건 매칭이 안 될 때 벡터 유사도로 돌려줄 요금제 수). */
	@Value("${search.plan-eval.top-ks:3,10}")
	private List<Integer> topKs;

	/** 평가에 쓸 질문 변환기의 빈 이름. 기본은 변환 없음. */
	@Value("${search.plan-eval.query-transformer:" + IdentityQueryTransformer.BEAN_NAME + "}")
	private String queryTransformerName;

	/** 평가에 쓸 FAQ 후보 검색기의 빈 이름. 요금제 평가에는 영향이 없지만 파이프라인 구성에 필요하다. */
	@Value("${search.plan-eval.faq-retriever:" + VectorFaqRetriever.BEAN_NAME + "}")
	private String faqRetrieverName;

	/**
	 * 질문 변환 결과를 저장·재사용하는 파일(JSONL). 비어 있으면 쓰지 않는다. LLM 변환은 실행마다 결과가 달라질 수 있어서, 변환 결과를
	 * 파일에 남겨 두면 같은 변환 결과로 요금제 개수나 검색 설정만 바꿔 다시 평가할 수 있다. 파일 하나는 하나의 실험(변환기·프롬프트 버전)
	 * 전용으로 쓴다. 상위 개수를 여러 개 재는 실행에서는 첫 번째 개수에서 변환하고 나머지는 저장된 결과를 쓴다.
	 */
	@Value("${search.plan-eval.transform-cache:}")
	private String transformCachePath;

	/** 최상급 질문에서 정형 조회가 돌려줄 요금제 개수. BE4는 개수를 따로 말하지 않은 질문에 1을 쓴다. */
	@Value("${search.plan-eval.extreme-limit:1}")
	private int extremeLimit;

	/** 질문 하나를 상위 개수 하나로 평가한 결과. */
	private record EvalRow(PlanEvalQuestion question, int topK, String path, boolean conditionMatched,
			List<String> delivered, Set<String> required, PlanEvalMetrics.Score score, boolean leaked,
			boolean alternativeDelivered, boolean truncated, TransformedQuery transformed) {

		/** 일반·최상급 유형(정답이 있는 질문)인지. */
		boolean scored() {
			return score != null;
		}
	}

	@Override
	public void run(String... args) {
		List<PlanEvalQuestion> questions = queryReader.read(resourceLoader.getResource(resourceLocation), PlanEvalQuestion.class);
		verifyPlansLoaded(questions);

		QueryTransformer rawTransformer = RetrievalPipelineConfig.pick(queryTransformers, queryTransformerName, "search.plan-eval.query-transformer");
		FaqRetriever retriever = RetrievalPipelineConfig.pick(faqRetrievers, faqRetrieverName, "search.plan-eval.faq-retriever");
		// 변환 결과 저장 파일이 지정되면 저장된 결과를 다시 쓴다(저장된 변환이 없는 질문만 실제 변환기를 부른다).
		CachedQueryTransformer cachedTransformer = transformCachePath.isBlank()
				? null : new CachedQueryTransformer(rawTransformer, Path.of(transformCachePath));
		RetrievalPipeline pipeline = servicePipeline.with(cachedTransformer != null ? cachedTransformer : rawTransformer, retriever);
		log.info("요금제 평가셋 {}문항, 상위 개수 {}, 요금제 threshold {}, 질문 변환기 {}, 후보 검색기 {}", questions.size(), topKs,
				pipeline.settings().planThreshold(), queryTransformerName, faqRetrieverName);
		if (cachedTransformer != null) {
			log.info("질문 변환 결과 저장 파일 사용: {} (저장된 변환 {}건)", transformCachePath, cachedTransformer.size());
		}

		List<EvalRow> all = new ArrayList<>();
		for (int topK : topKs) {
			List<EvalRow> rows = new ArrayList<>();
			for (PlanEvalQuestion question : questions) {
				rows.add(evaluate(question, pipeline, topK));
			}
			printSummary(rows, topK, pipeline.settings().planMatchedLimit());
			all.addAll(rows);
		}
		if (cachedTransformer != null) {
			cachedTransformer.save();
			log.info("질문 변환 결과 저장 파일: 저장된 결과 사용 {}건, 새로 변환 {}건, 일시 실패로 저장하지 않음 {}건 → 파일 {}건",
					cachedTransformer.hits(), cachedTransformer.misses(), cachedTransformer.skippedTransient(), cachedTransformer.size());
		}
		writeCsvReport(all);
		log.info("PLAN EVAL DONE");
	}

	/**
	 * 평가셋의 정답·대안 요금제가 모두 DB에 있는지 확인한다. 요금제 데이터가 평가셋을 만들 때와 다르면 모든 지표가
	 * 의미가 없어지므로, 조용히 낮은 점수를 내는 대신 바로 중단한다.
	 */
	private void verifyPlansLoaded(List<PlanEvalQuestion> questions) {
		Set<String> loaded = new HashSet<>(jdbcTemplate.queryForList(
				"SELECT plan_code FROM plans WHERE status = 'ACTIVE'", String.class));
		Set<String> missing = new TreeSet<>();
		for (PlanEvalQuestion question : questions) {
			missing.addAll(question.relevantCodes(1));
			missing.addAll(question.alternatives());
		}
		missing.removeAll(loaded);
		if (!missing.isEmpty()) {
			throw new IllegalStateException("평가셋의 요금제 " + missing.size() + "개가 DB에 없습니다: " + missing
					+ ". 요금제 데이터가 평가셋을 만들 때와 같은지 확인하세요.");
		}
	}

	private EvalRow evaluate(PlanEvalQuestion question, RetrievalPipeline pipeline, int topK) {
		List<String> delivered;
		boolean conditionMatched = false;
		String path;
		TransformedQuery transformed;
		if (TYPE_EXTREME.equals(question.type())) {
			// BE4는 최상급 의도를 알아내면 검색 결과 대신 정형 조회 결과를 쓴다.
			delivered = planLookupService.findExtremeForQuery(PlanSortKey.valueOf(question.sortKey()), extremeLimit, question.query())
					.stream().map(PlanReference::planCode).toList();
			path = "lookup";
			// 정형 조회는 질문 변환을 거치지 않고 원문으로 부른다(BE4의 최상급 분기).
			transformed = TransformedQuery.unchanged(question.query());
		} else {
			RetrievalResult result = pipeline.run(question.query(),
					RetrievalOptions.forEval(FAQ_TOP_K, FaqCandidateSelector.poolSize(FAQ_TOP_K), true).withPlanTopK(topK));
			delivered = result.context().planReferences().stream().map(PlanReference::planCode).toList();
			conditionMatched = result.planOutcome().conditionMatched();
			path = "search";
			transformed = result.query();
		}

		Set<String> required = question.expectNone() || question.noExactMatch() ? Set.of() : PlanEvalMetrics.requiredCodes(question);
		if (question.expectNone()) {
			return new EvalRow(question, topK, path, conditionMatched, delivered, required, null,
					PlanEvalMetrics.leaked(delivered), false, false, transformed);
		}
		if (question.noExactMatch()) {
			return new EvalRow(question, topK, path, conditionMatched, delivered, required, null, false,
					PlanEvalMetrics.alternativeDelivered(question, delivered), false, transformed);
		}

		PlanEvalMetrics.Score score = PlanEvalMetrics.score(question, delivered);
		// 조건 매칭 결과가 상한에 닿았는데 꼭 나와야 하는 요금제가 빠졌으면 잘린 것이다.
		boolean truncated = conditionMatched && delivered.size() >= Math.max(topK, pipeline.settings().planMatchedLimit())
				&& !new LinkedHashSet<>(delivered).containsAll(required);
		return new EvalRow(question, topK, path, conditionMatched, delivered, required, score, false, false, truncated, transformed);
	}

	// ---- 출력 ----

	private void printSummary(List<EvalRow> rows, int topK, int matchedLimit) {
		List<EvalRow> scored = rows.stream().filter(EvalRow::scored).toList();
		log.info("===== 요금제 평가: 요금제 개수 {} (정답이 있는 질문 {}개, 요금제 아님 {}개, 정확히 맞는 요금제 없음 {}개) =====", topK,
				scored.size(), count(rows, TYPE_NONE), count(rows, TYPE_NO_EXACT));
		log.info("Recall은 정답 요금제가 전달된 비율, 정밀도는 전달된 것 중 정답지에 있는 비율, 정확 일치는 꼭 나와야 하는 요금제를 모두 전달하고 오답이 없는 비율이다. "
				+ "최상급은 정형 조회 경로(개수 {})로 평가한다.", extremeLimit);
		log.info("전체: {}", metricsLine(scored));

		Map<String, List<EvalRow>> byType = new LinkedHashMap<>();
		for (EvalRow row : scored) {
			byType.computeIfAbsent(row.question().type(), key -> new ArrayList<>()).add(row);
		}
		byType.forEach((type, group) -> log.info("{} ({}개): {}", type, group.size(), metricsLine(group)));
		printFallbackSummary(rows, topK);

		List<EvalRow> none = rows.stream().filter(r -> TYPE_NONE.equals(r.question().type())).toList();
		long leaked = none.stream().filter(EvalRow::leaked).count();
		log.info("요금제 아님 ({}개): 요금제가 전달된 질문(누수) {}개 ({})", none.size(), leaked, pct(none.isEmpty() ? 0 : (double) leaked / none.size()));
		none.stream().filter(EvalRow::leaked).forEach(r -> log.info("  누수 {} | {} → {}", r.question().qid(), r.question().query(), r.delivered()));

		List<EvalRow> noExact = rows.stream().filter(r -> TYPE_NO_EXACT.equals(r.question().type())).toList();
		long alternative = noExact.stream().filter(EvalRow::alternativeDelivered).count();
		log.info("정확히 맞는 요금제 없음 ({}개): 안내할 대안이 전달된 질문 {}개 ({})", noExact.size(), alternative,
				pct(noExact.isEmpty() ? 0 : (double) alternative / noExact.size()));
		noExact.forEach(r -> log.info("  {} | {} → 전달 {} / 대안 {}", r.question().qid(), r.question().query(), r.delivered(),
				r.question().alternatives()));

		List<EvalRow> truncated = scored.stream().filter(EvalRow::truncated).toList();
		log.info("조건 매칭 결과가 상한({}개)에 닿아 정답이 잘린 질문: {}개", Math.max(topK, matchedLimit), truncated.size());
		truncated.forEach(r -> log.info("  잘림 {} | {} → 전달 {}개, 빠진 정답 {}", r.question().qid(), r.question().query(),
				r.delivered().size(), missing(r)));

		List<EvalRow> wrong = scored.stream().filter(r -> r.score().exact() == 0.0).toList();
		log.info("정확 일치가 아닌 질문 {}개 (앞 25개): 꼭 나와야 하는 요금제 대비 빠진 것 / 틀린 것", wrong.size());
		wrong.stream().limit(25).forEach(r -> log.info("  {} [{}{}] {} → 전달 {} | 빠짐 {} | 틀림 {}", r.question().qid(), r.question().type(),
				r.question().fuzzy() ? ", 애매함" : r.question().knownGap() ? ", 알려진 한계" : "", r.question().query(),
				r.delivered(), missing(r), wrongOnes(r)));
	}

	/**
	 * 질문 변환 현황. 변환기를 쓰면 변환 결과의 종류와 폴백(원문으로 돌아감) 사유별 건수, 그리고 요금제 검색에 변환된 질문이 쓰인 질문과
	 * 원문이 쓰인 질문의 점수를 따로 낸다. 폴백으로 원문이 쓰인 질문이 섞여 있으면 "질문 변환 효과"가 실제보다 Baseline에 가까워지므로,
	 * 두 집단을 나눠 봐야 한다. 정형 조회(최상급) 경로는 질문 변환을 거치지 않아 집계에서 뺀다. 변환기가 질문을 바꾸지 않는
	 * 구현(Baseline)이면 변환 폴백은 집계 대상이 아니다.
	 */
	private void printFallbackSummary(List<EvalRow> rows, int topK) {
		List<EvalRow> searched = rows.stream().filter(row -> "search".equals(row.path())).toList();
		log.info("===== 질문 변환 현황 (요금제 개수 {}, 검색 경로 질문 {}개) =====", topK, searched.size());

		Map<TransformInfo.Kind, Long> byKind = new EnumMap<>(TransformInfo.Kind.class);
		for (EvalRow row : searched) {
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
		searched.stream().map(row -> row.transformed().info())
				.filter(info -> info.kind() == TransformInfo.Kind.FALLBACK_ORIGINAL)
				.forEach(info -> reasons.merge(info.reason(), 1L, Long::sum));
		log.info("원문 폴백 사유: {}", reasons.isEmpty() ? "없음" : reasons);

		long planNull = searched.stream().filter(row -> row.transformed().info().planNull()).count();
		long faqNull = searched.stream().filter(row -> row.transformed().info().faqNull()).count();
		long sameText = searched.stream().filter(this::planQueryTransformed)
				.filter(row -> row.transformed().planQuery().equals(row.transformed().original())).count();
		log.info("한쪽만 비어 있음: 요금제용 {}개(요금제 검색은 원문으로 진행), FAQ용 {}개(요금제 질문 평가에서는 정상) | 변환됐지만 요금제용 질문이 원문과 같은 질문: {}개",
				planNull, faqNull, sameText);

		long planOriginalCount = searched.stream().filter(row -> !planQueryTransformed(row)).count();
		long fallbackCount = byKind.getOrDefault(TransformInfo.Kind.FALLBACK_ORIGINAL, 0L);
		log.info("요금제 변환 적용률 {} | 요금제 검색에 원문을 쓴 비율 {} (원문 폴백 {} + 요금제용만 비어 있음 {})",
				rate(searched.size() - planOriginalCount, searched.size()), rate(planOriginalCount, searched.size()),
				rate(fallbackCount, searched.size()), rate(planOriginalCount - fallbackCount, searched.size()));

		printGroupScores("요금제 검색에 변환된 질문을 쓴 질문", searched.stream().filter(EvalRow::scored).filter(this::planQueryTransformed).toList());
		printGroupScores("요금제 검색에 원문을 쓴 질문(폴백·요금제용 비어 있음)",
				searched.stream().filter(EvalRow::scored).filter(row -> !planQueryTransformed(row)).toList());
	}

	/** 요금제 검색에 변환된 질문이 실제로 쓰였는지. 원문으로 폴백했거나 요금제용 질문이 비어 있으면(원문으로 채움) false. */
	private boolean planQueryTransformed(EvalRow row) {
		TransformInfo info = row.transformed().info();
		return info.applied() && !info.planNull();
	}

	private void printGroupScores(String label, List<EvalRow> group) {
		if (group.isEmpty()) {
			log.info("{}: 0개", label);
			return;
		}
		log.info("{} ({}개): {}", label, group.size(), metricsLine(group));
	}

	private String metricsLine(List<EvalRow> rows) {
		long empty = rows.stream().filter(r -> r.delivered().isEmpty()).count();
		return String.format(Locale.ROOT,
				"Recall(1점↑) %s | Recall(2점↑) %s | 정밀도 %s | 정확 일치 %s | Hit@1 %s | MRR %s | nDCG@10 %s | 평균 전달 %s개 | 빈 응답 %d개",
				pct(avg(rows, r -> r.score().recallAll())), pct(avg(rows, r -> r.score().recallStrong())),
				pct(avg(rows, r -> r.score().precision())), pct(avg(rows, r -> r.score().exact())),
				pct(avg(rows, r -> r.score().hit1())), pct(avg(rows, r -> r.score().mrr())),
				pct(avg(rows, r -> r.score().ndcg())), fmt(avg(rows, r -> r.score().delivered())), empty);
	}

	private static long count(List<EvalRow> rows, String type) {
		return rows.stream().filter(r -> type.equals(r.question().type())).count();
	}

	private static List<String> missing(EvalRow row) {
		return row.required().stream().filter(code -> !row.delivered().contains(code)).toList();
	}

	private static List<String> wrongOnes(EvalRow row) {
		Set<String> allowed = row.question().relevantCodes(1);
		return row.delivered().stream().filter(code -> !allowed.contains(code)).toList();
	}

	// ---- CSV ----

	private void writeCsvReport(List<EvalRow> rows) {
		Path dir = Path.of("build", "regression-report");
		Path file = dir.resolve("plan-eval-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".csv");
		try {
			Files.createDirectories(dir);
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				// 엑셀이 한글을 깨뜨리지 않도록 UTF-8 BOM을 붙인다.
				writer.write('﻿');
				writer.write("qid,type,style,query,topK,path,conditionMatched,deliveredCount,delivered,required,"
						+ "recallAll,recallStrong,precision,exact,hit1,mrr,ndcg,leaked,alternativeDelivered,truncated,knownGap,fuzzy,"
						+ "transformKind,fallbackReason,faqNull,planNull,faqQuery,planQuery\n");
				for (EvalRow row : rows) {
					writer.write(csvLine(row) + "\n");
				}
			}
			log.info("요금제 평가 리포트 저장: {}", file.toAbsolutePath());
		} catch (IOException exception) {
			log.warn("요금제 평가 리포트 저장 실패: {}", exception.getMessage());
		}
	}

	private String csvLine(EvalRow row) {
		PlanEvalQuestion q = row.question();
		PlanEvalMetrics.Score s = row.score();
		return String.join(",", q.qid(), q.type(), q.style(), csv(q.query()), String.valueOf(row.topK()), row.path(),
				flag(row.conditionMatched()), String.valueOf(row.delivered().size()), csv(String.join("|", row.delivered())),
				csv(String.join("|", row.required())),
				s == null ? "" : num(s.recallAll()), s == null ? "" : num(s.recallStrong()), s == null ? "" : num(s.precision()),
				s == null ? "" : num(s.exact()), s == null ? "" : num(s.hit1()), s == null ? "" : num(s.mrr()),
				s == null ? "" : num(s.ndcg()), flag(row.leaked()), flag(row.alternativeDelivered()), flag(row.truncated()),
				flag(q.knownGap()), flag(q.fuzzy()),
				row.transformed().info().kind().name(), row.transformed().info().reason(),
				flag(row.transformed().info().faqNull()), flag(row.transformed().info().planNull()),
				csv(oneLine(row.transformed().faqQuery())), csv(oneLine(row.transformed().planQuery())));
	}

	/** 줄바꿈을 글자 그대로 "\n"로 바꿔 CSV 한 줄이 깨지지 않게 한다. */
	private static String oneLine(String value) {
		return value == null ? "" : value.replace("\r", "").replace("\n", "\\n");
	}

	private static String flag(boolean value) {
		return value ? "Y" : "N";
	}

	private static String csv(String value) {
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	// ---- 계산 보조 ----

	/** NaN(계산할 수 없는 값)은 평균에서 제외한다. */
	private static double avg(List<EvalRow> rows, ToDoubleFunction<EvalRow> getter) {
		return rows.stream().mapToDouble(getter).filter(value -> !Double.isNaN(value)).average().orElse(0.0);
	}

	private static String num(double value) {
		return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.4f", value);
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
