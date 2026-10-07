package com.vita.search.eval;

import com.vita.search.dto.PlanReference;
import com.vita.search.pipeline.FaqRetriever;
import com.vita.search.pipeline.IdentityQueryTransformer;
import com.vita.search.pipeline.QueryTransformer;
import com.vita.search.pipeline.RetrievalOptions;
import com.vita.search.pipeline.RetrievalPipeline;
import com.vita.search.pipeline.RetrievalPipelineConfig;
import com.vita.search.pipeline.RetrievalResult;
import com.vita.search.pipeline.VectorFaqRetriever;
import com.vita.search.regression.RegressionQueryReader;
import com.vita.search.service.FaqCandidateSelector;
import com.vita.search.service.PlanLookupService;
import com.vita.search.service.PlanSearchService;
import com.vita.search.service.PlanSortKey;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p>상위 개수(topK)는 {@code search.plan-eval.top-ks}(기본 3,10)로 여러 값을 한 번에 잰다. 현재 서비스가 쓰는 3과 합의한 10을 비교하기 위해서다.
 * 질문 변환기·검색기는 {@code search.plan-eval.query-transformer}, {@code search.plan-eval.faq-retriever}로 갈아끼울 수 있다
 * (질문 변환의 요금제용 질문 {@code planQuery} 효과를 재는 용도). 로컬 도커(DB+임베딩 서버)가 떠 있고 요금제 15종이 DB에 있을 때
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

	/** 비교할 상위 개수 목록. 현재 서비스(BE4) 값 3과 합의한 값 10을 함께 잰다. */
	@Value("${search.plan-eval.top-ks:3,10}")
	private List<Integer> topKs;

	/** 평가에 쓸 질문 변환기의 빈 이름. 기본은 변환 없음. */
	@Value("${search.plan-eval.query-transformer:" + IdentityQueryTransformer.BEAN_NAME + "}")
	private String queryTransformerName;

	/** 평가에 쓸 FAQ 후보 검색기의 빈 이름. 요금제 평가에는 영향이 없지만 파이프라인 구성에 필요하다. */
	@Value("${search.plan-eval.faq-retriever:" + VectorFaqRetriever.BEAN_NAME + "}")
	private String faqRetrieverName;

	/** 최상급 질문에서 정형 조회가 돌려줄 요금제 개수. BE4는 개수를 따로 말하지 않은 질문에 1을 쓴다. */
	@Value("${search.plan-eval.extreme-limit:1}")
	private int extremeLimit;

	/** 질문 하나를 상위 개수 하나로 평가한 결과. */
	private record EvalRow(PlanEvalQuestion question, int topK, String path, boolean conditionMatched,
			List<String> delivered, Set<String> required, PlanEvalMetrics.Score score, boolean leaked,
			boolean alternativeDelivered, boolean truncated) {

		/** 일반·최상급 유형(정답이 있는 질문)인지. */
		boolean scored() {
			return score != null;
		}
	}

	@Override
	public void run(String... args) {
		List<PlanEvalQuestion> questions = queryReader.read(resourceLoader.getResource(resourceLocation), PlanEvalQuestion.class);
		verifyPlansLoaded(questions);

		RetrievalPipeline pipeline = servicePipeline.with(
				RetrievalPipelineConfig.pick(queryTransformers, queryTransformerName, "search.plan-eval.query-transformer"),
				RetrievalPipelineConfig.pick(faqRetrievers, faqRetrieverName, "search.plan-eval.faq-retriever"));
		log.info("요금제 평가셋 {}문항, 상위 개수 {}, 요금제 threshold {}, 질문 변환기 {}, 후보 검색기 {}", questions.size(), topKs,
				pipeline.settings().planThreshold(), queryTransformerName, faqRetrieverName);

		List<EvalRow> all = new ArrayList<>();
		for (int topK : topKs) {
			List<EvalRow> rows = new ArrayList<>();
			for (PlanEvalQuestion question : questions) {
				rows.add(evaluate(question, pipeline, topK));
			}
			printSummary(rows, topK);
			all.addAll(rows);
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
		if (TYPE_EXTREME.equals(question.type())) {
			// BE4는 최상급 의도를 알아내면 검색 결과 대신 정형 조회 결과를 쓴다.
			delivered = planLookupService.findExtremeForQuery(PlanSortKey.valueOf(question.sortKey()), extremeLimit, question.query())
					.stream().map(PlanReference::planCode).toList();
			path = "lookup";
		} else {
			RetrievalResult result = pipeline.run(question.query(),
					RetrievalOptions.forEval(topK, FaqCandidateSelector.poolSize(topK), true));
			delivered = result.context().planReferences().stream().map(PlanReference::planCode).toList();
			conditionMatched = result.planOutcome().conditionMatched();
			path = "search";
		}

		Set<String> required = question.expectNone() || question.noExactMatch() ? Set.of() : PlanEvalMetrics.requiredCodes(question);
		if (question.expectNone()) {
			return new EvalRow(question, topK, path, conditionMatched, delivered, required, null,
					PlanEvalMetrics.leaked(delivered), false, false);
		}
		if (question.noExactMatch()) {
			return new EvalRow(question, topK, path, conditionMatched, delivered, required, null, false,
					PlanEvalMetrics.alternativeDelivered(question, delivered), false);
		}

		PlanEvalMetrics.Score score = PlanEvalMetrics.score(question, delivered);
		// 조건 매칭 결과가 상한에 닿았는데 꼭 나와야 하는 요금제가 빠졌으면 잘린 것이다.
		boolean truncated = conditionMatched && delivered.size() >= Math.max(topK, PlanSearchService.MATCHED_RESULT_LIMIT)
				&& !new LinkedHashSet<>(delivered).containsAll(required);
		return new EvalRow(question, topK, path, conditionMatched, delivered, required, score, false, false, truncated);
	}

	// ---- 출력 ----

	private void printSummary(List<EvalRow> rows, int topK) {
		List<EvalRow> scored = rows.stream().filter(EvalRow::scored).toList();
		log.info("===== 요금제 평가: 상위 {}개 (정답이 있는 질문 {}개, 요금제 아님 {}개, 정확히 맞는 요금제 없음 {}개) =====", topK,
				scored.size(), count(rows, TYPE_NONE), count(rows, TYPE_NO_EXACT));
		log.info("Recall은 정답 요금제가 전달된 비율, 정밀도는 전달된 것 중 정답지에 있는 비율, 정확 일치는 꼭 나와야 하는 요금제를 모두 전달하고 오답이 없는 비율이다. "
				+ "최상급은 정형 조회 경로(개수 {})로 평가한다.", extremeLimit);
		log.info("전체: {}", metricsLine(scored));

		Map<String, List<EvalRow>> byType = new LinkedHashMap<>();
		for (EvalRow row : scored) {
			byType.computeIfAbsent(row.question().type(), key -> new ArrayList<>()).add(row);
		}
		byType.forEach((type, group) -> log.info("{} ({}개): {}", type, group.size(), metricsLine(group)));

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
		log.info("조건 매칭 결과가 상한({}개)에 닿아 정답이 잘린 질문: {}개", Math.max(topK, PlanSearchService.MATCHED_RESULT_LIMIT), truncated.size());
		truncated.forEach(r -> log.info("  잘림 {} | {} → 전달 {}개, 빠진 정답 {}", r.question().qid(), r.question().query(),
				r.delivered().size(), missing(r)));

		List<EvalRow> wrong = scored.stream().filter(r -> r.score().exact() == 0.0).toList();
		log.info("정확 일치가 아닌 질문 {}개 (앞 25개): 꼭 나와야 하는 요금제 대비 빠진 것 / 틀린 것", wrong.size());
		wrong.stream().limit(25).forEach(r -> log.info("  {} [{}{}] {} → 전달 {} | 빠짐 {} | 틀림 {}", r.question().qid(), r.question().type(),
				r.question().fuzzy() ? ", 애매함" : r.question().knownGap() ? ", 알려진 한계" : "", r.question().query(),
				r.delivered(), missing(r), wrongOnes(r)));
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
						+ "recallAll,recallStrong,precision,exact,hit1,mrr,ndcg,leaked,alternativeDelivered,truncated,knownGap,fuzzy\n");
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
				flag(q.knownGap()), flag(q.fuzzy()));
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

	private static String pct(double value) {
		return String.format(Locale.ROOT, "%.1f%%", value * 100);
	}

	private static String fmt(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
