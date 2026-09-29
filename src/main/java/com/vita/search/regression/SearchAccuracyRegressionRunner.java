package com.vita.search.regression;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqVectorSearchRepository;
import com.vita.search.repository.PlanVectorSearchRepository;
import com.vita.search.service.FaqCandidateSelector;
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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
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
	private final RegressionQueryReader queryReader;

	/** top은 실제로 검색된 상위 결과(CSV에서 어떤 답이 나왔는지 확인하기 위해 보관). */
	private record FaqResultRow(String category, String subcategory, String style, String query,
			boolean top1Match, boolean top3Match, double top1Similarity, List<FaqSimilarityResult> top) {
	}

	private record PlanResultRow(String planCode, String aspect, String query,
			boolean top1Match, boolean top3Match, double top1Similarity, List<PlanSimilarityResult> top) {
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
		List<PlanResultRow> planResults = planQueries.stream().map(this::evaluatePlanQuery).toList();
		List<NegativeResultRow> negativeResults = negativeQueries.stream().map(this::evaluateNegativeQuery).toList();

		printFaqSummary(faqResults);
		printPlanSummary(planResults);
		printNegativeSummary(negativeResults);
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
		double top1Similarity = results.isEmpty() ? 0.0 : results.get(0).similarity();
		return new FaqResultRow(item.category(), item.subcategory(), item.style(), item.query(),
				top1Match, top3Match, top1Similarity, results);
	}

	private boolean matchesFaq(FaqSimilarityResult result, FaqRegressionQuery item) {
		return result.category().equals(item.category()) && result.subcategory().equals(item.subcategory());
	}

	private PlanResultRow evaluatePlanQuery(PlanRegressionQuery item) {
		float[] vector = embeddingProvider.embedQuery(item.query());
		List<PlanSimilarityResult> results = planVectorSearchRepository.searchBySimilarity(vector, 0.0, TOP_K);
		boolean top1Match = !results.isEmpty() && results.get(0).planCode().equals(item.planCode());
		boolean top3Match = results.stream().anyMatch(r -> r.planCode().equals(item.planCode()));
		double top1Similarity = results.isEmpty() ? 0.0 : results.get(0).similarity();
		return new PlanResultRow(item.planCode(), item.aspect(), item.query(), top1Match, top3Match, top1Similarity, results);
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

		Map<String, List<FaqResultRow>> byStyle = results.stream().collect(Collectors.groupingBy(FaqResultRow::style));
		byStyle.entrySet().stream()
				.sorted(Comparator.comparing(Map.Entry::getKey))
				.forEach(entry -> {
					List<FaqResultRow> rows = entry.getValue();
					double rate = 100.0 * rows.stream().filter(FaqResultRow::top1Match).count() / rows.size();
					log.info("  스타일별 [{}] Top-1 정확도: {}% ({}문항)", entry.getKey(), String.format("%.1f", rate), rows.size());
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
		log.info("Top-1 정확도: {}%  Top-3 정확도: {}%", String.format("%.1f", top1Rate), String.format("%.1f", top3Rate));
		log.info("Top-1 정답 중 최저 유사도(현재 threshold=0.81과 비교): {}", String.format("%.4f", minTop1SimilarityOfMatches));

		Map<String, List<PlanResultRow>> byAspect = results.stream().collect(Collectors.groupingBy(PlanResultRow::aspect));
		byAspect.entrySet().stream()
				.sorted(Comparator.comparing(Map.Entry::getKey))
				.forEach(entry -> {
					List<PlanResultRow> rows = entry.getValue();
					double rate = 100.0 * rows.stream().filter(PlanResultRow::top1Match).count() / rows.size();
					log.info("  유형별 [{}] Top-1 정확도: {}% ({}문항)", entry.getKey(), String.format("%.1f", rate), rows.size());
				});

		results.stream().filter(r -> !r.top3Match()).forEach(r ->
				log.info("  미스(Top-3에도 없음): [{}][{}] \"{}\" (top1 유사도={})",
						r.planCode(), r.aspect(), r.query(), String.format("%.4f", r.top1Similarity())));
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
						+ "r3_similarity,r3_label,r3_question,r3_answer\n");
				for (FaqResultRow r : faqResults) {
					writeCsvRow(writer, "FAQ", r.category() + "/" + r.subcategory(), r.style(), r.query(),
							String.valueOf(r.top1Match()), String.valueOf(r.top3Match()), r.top1Similarity(),
							"", "", "", faqCells(r.top()));
				}
				for (PlanResultRow r : planResults) {
					writeCsvRow(writer, "PLAN", r.planCode(), r.aspect(), r.query(),
							String.valueOf(r.top1Match()), String.valueOf(r.top3Match()), r.top1Similarity(),
							"", "", "", planCells(r.top()));
				}
				for (NegativeResultRow r : negativeResults) {
					String planName = r.planTop1() == null ? "" : r.planTop1().name();
					writeCsvRow(writer, "NEGATIVE", r.topic(), "-", r.query(), "false", "false",
							Math.max(r.faqTop1Similarity(), r.planTop1Similarity()),
							String.format("%.4f", r.faqTop1Similarity()),
							String.format("%.4f", r.planTop1Similarity()),
							planName, faqCells(r.faqTop()));
				}
			}
			log.info("CSV 리포트 저장: {}", file.toAbsolutePath());
		} catch (IOException exception) {
			log.warn("CSV 리포트 저장 실패 (요약 로그는 정상 출력됨): {}", exception.getMessage());
		}
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
