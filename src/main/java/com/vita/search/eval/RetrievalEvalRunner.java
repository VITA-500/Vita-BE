package com.vita.search.eval;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.regression.RegressionQueryReader;
import com.vita.search.repository.FaqVectorSearchRepository;
import com.vita.search.service.FaqCandidateSelector;
import com.vita.search.service.FaqCategoryTermBooster;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * 검색 평가셋(v2)의 질문마다 실제 검색 로직으로 후보를 뽑아 정답지와 비교하고, 검색 품질 지표를 낸다.
 *
 * <p>측정하는 것은 두 단계다.
 * <ol>
 *   <li>풀(후보 N개) 단계 — 유사도 상위 N개 안에 정답이 얼마나 들어왔는지(Recall, 정답 판정은 관련도 1점 이상).
 *       N은 {@code search.eval.pool-sizes}(기본 10,20,30,50)로 바꿔 가며 비교한다. 실제 검색은 topK의 10배(=30)를 가져온다.</li>
 *   <li>상위 3개 단계 — 풀에 분류 이름 가산점 재정렬과 답변 중복 제거를 적용해 뽑은 상위 3개가 정답인지
 *       (Precision@3, nDCG@3, MRR, Hit@1/@3, 정답 판정은 관련도 2점 이상). 실제 검색 로직({@code FaqRetrievalServiceImpl})과
 *       같은 부품을 같은 순서로 쓰고, threshold는 마지막에 따로 적용해 "threshold 통과 후" 지표를 함께 낸다.</li>
 * </ol>
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

	/** 상위 몇 개를 최종 결과로 볼지. 실제 검색(BE4 호출)의 topK와 같다. */
	private static final int TOP_K = 3;

	/** 풀 단계 Recall에서 정답으로 보는 최소 관련도. */
	private static final int POOL_MIN_GRADE = 1;

	/** 상위 3개 단계 지표에서 정답으로 보는 최소 관련도. */
	private static final int TOP_MIN_GRADE = 2;

	/** 부트스트랩 반복에 쓰는 고정 시드(실행마다 같은 값이 나오게 한다). */
	private static final long BOOTSTRAP_SEED = 20261006L;

	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

	private final EmbeddingProvider embeddingProvider;
	private final FaqVectorSearchRepository faqVectorSearchRepository;
	private final RegressionQueryReader queryReader;
	private final ResourceLoader resourceLoader;
	private final JdbcTemplate jdbcTemplate;

	/** 평가셋 위치. classpath: 또는 file: 형식. */
	@Value("${search.eval.resource:classpath:data/regression/eval_v2/retrieval_eval_v2.jsonl}")
	private String resourceLocation;

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

	/** 실제 검색과 같은 threshold. "관련 없음" 처리 기준으로 쓰인다. */
	@Value("${retrieval.similarity-threshold:0.83}")
	private double similarityThreshold;

	/** 실제 검색과 같은 분류 이름 가산점. */
	@Value("${retrieval.category-boost.bonus:0.01}")
	private double categoryBoostBonus;

	/** 질문 하나를 풀 크기 하나로 평가한 결과. */
	private record EvalRow(RetrievalEvalQuestion question, int poolSize, int relevantCount,
			double recallPool1, double recallPool2, int distinctInPool, int maxDuplicates,
			List<FaqSimilarityResult> top, List<String> topIds, Map<String, Integer> grades,
			double precision3, double ndcg3, double mrr, double hit1, double hit3,
			int passedThreshold, double e2ePrecision3, double e2eHit3, boolean ruleBlocked) {

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

		List<Integer> sizes = effectivePoolSizes();
		log.info("평가셋 {}문항, 풀 크기 {}, 상위 {}개, threshold {}, 가산점 {}", questions.size(), sizes, TOP_K,
				similarityThreshold, categoryBoostBonus);

		List<EvalRow> rows = new ArrayList<>();
		for (RetrievalEvalQuestion question : questions) {
			float[] vector = embeddingProvider.embedQuery(question.query());
			boolean ruleBlocked = !IrrelevantQueryDetector.detect(question.query()).isEmpty();
			for (int size : sizes) {
				rows.add(evaluate(question, vector, size, sourceIds, ruleBlocked));
			}
		}

		List<EvalRow> mainRows = rows.stream()
				.filter(row -> row.poolSize() == mainPoolSize)
				.sorted(Comparator.comparingInt(row -> row.question().subsetOrder()))
				.toList();

		printPoolSizeSummary(rows, sizes);
		printStyleSummary(mainRows);
		printSubsetStability(mainRows);
		printWeakQuestions(mainRows);
		writeCsvReport(rows);
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

	private EvalRow evaluate(RetrievalEvalQuestion question, float[] vector, int poolSize,
			Map<Long, String> sourceIds, boolean ruleBlocked) {
		// 실제 검색과 같이 threshold 없이 가까운 순으로 poolSize개를 가져온다.
		List<FaqSimilarityResult> pool = faqVectorSearchRepository.searchBySimilarity(
				vector, FaqStatus.ACTIVE, 0.0, poolSize);
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

		// 실제 검색과 같은 순서: 분류 이름 가산점 재정렬 → 답변 중복 제거 → 상위 TOP_K → threshold.
		List<FaqSimilarityResult> ranked = FaqCategoryTermBooster.rerank(question.query(), pool, categoryBoostBonus);
		List<FaqSimilarityResult> top = FaqCandidateSelector.selectDistinct(ranked, TOP_K);
		List<String> topIds = top.stream().map(result -> idOf(result, sourceIds)).toList();
		List<String> passedIds = top.stream()
				.filter(result -> result.similarity() >= similarityThreshold)
				.map(result -> idOf(result, sourceIds))
				.toList();

		return new EvalRow(question, poolSize, question.relevantIds(POOL_MIN_GRADE).size(), recallPool1, recallPool2,
				answerCounts.size(), maxDuplicates, top, topIds, grades,
				RetrievalMetrics.precisionAtK(topIds, grades, TOP_K, TOP_MIN_GRADE),
				RetrievalMetrics.ndcgAtK(topIds, grades, question.groupGradesDescending(), TOP_K),
				RetrievalMetrics.reciprocalRank(topIds, grades, TOP_MIN_GRADE),
				RetrievalMetrics.hitAtK(topIds, grades, 1, TOP_MIN_GRADE),
				RetrievalMetrics.hitAtK(topIds, grades, TOP_K, TOP_MIN_GRADE),
				passedIds.size(),
				RetrievalMetrics.precisionAtK(passedIds, grades, TOP_K, TOP_MIN_GRADE),
				RetrievalMetrics.hitAtK(passedIds, grades, TOP_K, TOP_MIN_GRADE),
				ruleBlocked);
	}

	private String idOf(FaqSimilarityResult result, Map<Long, String> sourceIds) {
		return sourceIds.getOrDefault(result.id(), "ID-" + result.id());
	}

	// ---- 출력 ----

	/** 풀 크기별 요약. 후보 풀 크기(10/20/30/50) 비교 근거로 쓴다. */
	private void printPoolSizeSummary(List<EvalRow> rows, List<Integer> sizes) {
		log.info("===== 풀 크기별 지표 (질문 {}개) =====", rows.stream().filter(r -> r.poolSize() == sizes.get(0)).count());
		log.info("Recall은 관련도 1점 이상, 상위 3개 지표는 관련도 2점 이상을 정답으로 본다. 'threshold 후'는 유사도 {} 이상만 남긴 결과.",
				similarityThreshold);
		for (int size : sizes) {
			List<EvalRow> group = rows.stream().filter(r -> r.poolSize() == size).toList();
			long fewer = group.stream().filter(r -> r.distinctInPool() < TOP_K).count();
			log.info("풀 {}: Recall(1점↑) {} | Recall(2점↑) {} | P@3 {} | nDCG@3 {} | MRR {} | Hit@1 {} | Hit@3 {}",
					size, pct(avg(group, EvalRow::recallPool1)), pct(avg(group, EvalRow::recallPool2)),
					pct(avg(group, EvalRow::precision3)), pct(avg(group, EvalRow::ndcg3)), pct(avg(group, EvalRow::mrr)),
					pct(avg(group, EvalRow::hit1)), pct(avg(group, EvalRow::hit3)));
			log.info("      풀 안 서로 다른 답변 평균 {}개(최소 {}), 같은 답변 최대 반복 {}회, 서로 다른 답변이 3개 미만인 질문 {}개 | "
							+ "threshold 후: 남은 결과 평균 {}개, P@3 {}, Hit@3 {}, 규칙으로 막힌 질문 {}개",
					fmt(avg(group, r -> r.distinctInPool())), group.stream().mapToInt(EvalRow::distinctInPool).min().orElse(0),
					group.stream().mapToInt(EvalRow::maxDuplicates).max().orElse(0), fewer,
					fmt(avg(group, r -> r.passedThreshold())), pct(avg(group, EvalRow::e2ePrecision3)),
					pct(avg(group, EvalRow::e2eHit3)), group.stream().filter(EvalRow::ruleBlocked).count());
		}
	}

	private void printStyleSummary(List<EvalRow> mainRows) {
		log.info("===== 말투별 지표 (풀 {}) =====", mainPoolSize);
		Map<String, List<EvalRow>> byStyle = new LinkedHashMap<>();
		for (EvalRow row : mainRows) {
			byStyle.computeIfAbsent(row.question().style(), key -> new ArrayList<>()).add(row);
		}
		byStyle.forEach((style, group) -> log.info("{} ({}개): Recall {} | P@3 {} | nDCG@3 {} | MRR {} | Hit@3 {}",
				style, group.size(), pct(avg(group, EvalRow::recallPool1)), pct(avg(group, EvalRow::precision3)),
				pct(avg(group, EvalRow::ndcg3)), pct(avg(group, EvalRow::mrr)), pct(avg(group, EvalRow::hit3))));
	}

	/**
	 * 질문을 앞에서부터 N개로 자른 지표(평가셋의 subsetOrder 순서)와, 질문을 N개씩 무작위로 뽑는 일을 반복했을 때의
	 * 표준편차를 보여 준다. 값이 전체(마지막 행)와 거의 같고 표준편차가 충분히 작아지는 가장 작은 N이 안정적인 질문 수다.
	 */
	private void printSubsetStability(List<EvalRow> mainRows) {
		String[] names = {"Recall", "P@3", "nDCG@3", "MRR"};
		List<ToDoubleFunction<EvalRow>> getters = List.of(EvalRow::recallPool1, EvalRow::precision3, EvalRow::ndcg3, EvalRow::mrr);
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

	/** 정답을 거의 못 가져온 질문(상위 3개에 정답이 없거나 풀에 정답이 절반도 안 들어옴)을 나열한다. */
	private void printWeakQuestions(List<EvalRow> mainRows) {
		List<EvalRow> weak = mainRows.stream()
				.filter(row -> row.hit3() == 0.0 || row.recallPool1() < 0.5)
				.sorted(Comparator.comparingDouble(EvalRow::recallPool1))
				.toList();
		log.info("===== 약한 질문 (상위 3개에 정답 없음 또는 풀 Recall 50% 미만): {}개 =====", weak.size());
		weak.stream().limit(20).forEach(row -> log.info("{} [{}] Recall {} Hit@3 {} | {} | 1위: {}",
				row.question().qid(), row.question().style(), pct(row.recallPool1()), pct(row.hit3()),
				row.question().query(), row.top().isEmpty() ? "-" : row.top().get(0).question()));
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
				writer.write("qid,subsetOrder,style,query,poolSize,relevantCount,recallPool1,recallPool2,distinctInPool,maxDuplicates,"
						+ "precision3,ndcg3,mrr,hit1,hit3,passedThreshold,e2ePrecision3,e2eHit3,ruleBlocked,"
						+ "top1_id,top1_grade,top1_sim,top1_question,top2_id,top2_grade,top2_sim,top2_question,"
						+ "top3_id,top3_grade,top3_sim,top3_question\n");
				for (EvalRow row : rows) {
					writeRow(writer, row);
				}
			}
			log.info("검색 평가 리포트 저장: {}", file.toAbsolutePath());
		} catch (IOException exception) {
			log.warn("검색 평가 리포트 저장 실패: {}", exception.getMessage());
		}
	}

	private void writeRow(Writer writer, EvalRow row) throws IOException {
		RetrievalEvalQuestion q = row.question();
		StringBuilder sb = new StringBuilder();
		sb.append(q.qid()).append(',').append(q.subsetOrder()).append(',').append(q.style()).append(',')
				.append(csv(q.query())).append(',').append(row.poolSize()).append(',').append(row.relevantCount()).append(',')
				.append(num(row.recallPool1())).append(',').append(num(row.recallPool2())).append(',')
				.append(row.distinctInPool()).append(',').append(row.maxDuplicates()).append(',')
				.append(num(row.precision3())).append(',')
				.append(num(row.ndcg3())).append(',').append(num(row.mrr())).append(',').append(num(row.hit1())).append(',')
				.append(num(row.hit3())).append(',').append(row.passedThreshold()).append(',')
				.append(num(row.e2ePrecision3())).append(',').append(num(row.e2eHit3())).append(',')
				.append(row.ruleBlocked() ? "Y" : "N");
		for (int i = 0; i < TOP_K; i++) {
			if (i < row.top().size()) {
				FaqSimilarityResult result = row.top().get(i);
				String id = row.topIds().get(i);
				sb.append(',').append(id).append(',').append(row.grades().getOrDefault(id, 0)).append(',')
						.append(num(result.similarity())).append(',').append(csv(result.question()));
			} else {
				sb.append(",,,,");
			}
		}
		sb.append('\n');
		writer.write(sb.toString());
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

	private static String pct(double value) {
		return String.format(Locale.ROOT, "%.1f%%", value * 100);
	}

	private static String fmt(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
