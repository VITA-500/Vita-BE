package com.vita.search.pipeline;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanReference;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.service.FaqCandidateSelector;
import com.vita.search.service.FaqCategoryTermBooster;
import com.vita.search.service.IrrelevantQueryDetector;
import com.vita.search.service.PlanSearchService;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * RAG 검색의 공통 경로. 질문 한 개를 받아 FAQ·요금제 후보를 찾고, BE4에 전달할 {@link FaqRetrievalContext}를 만든다.
 * 실제 서비스({@code FaqRetrievalServiceImpl})와 평가 러너({@code RetrievalEvalRunner})가 같은 클래스를 부르므로,
 * 평가 점수가 실제 검색 동작과 어긋나지 않는다.
 *
 * <p>흐름과 교체 지점:
 * <ol>
 *   <li><b>질문 변환</b> — {@link QueryTransformer}. 영어 번역(BE1), 질문 재작성(BE4)이 끼는 자리.</li>
 *   <li><b>임베딩</b> — {@link EmbeddingProvider}. FAQ용·요금제용 질문을 벡터로 바꾼다(같은 질문이면 한 번만).</li>
 *   <li><b>FAQ 후보 검색</b> — {@link FaqRetriever}. 영어 컬럼 검색(BE1), Hybrid 검색(BE6)이 끼는 자리.</li>
 *   <li><b>요금제 검색</b> — {@link PlanSearchService}. 벡터 유사도에 가격·데이터량 같은 조건 매칭을 더한다.</li>
 *   <li><b>Context 구성</b> — 무관 질문 규칙, 분류 가산점 재정렬, 같은 답변 중복 제거, topK, threshold 판정, 응답 객체 변환.
 *       이 후처리는 모든 변형이 공통으로 쓴다({@link #selectFaq}).</li>
 * </ol>
 * 각 단계의 소요 시간은 {@link StageTimings}에 담긴다. 이 클래스는 상태가 없어 여러 스레드에서 함께 써도 안전하다.
 */
@Slf4j
public class RetrievalPipeline {

	/** 한글/영문/숫자가 2자 이상 연속된 덩어리만 키워드로 취급 (조사 등 형태소 분리는 안 함 — 근사치). */
	private static final Pattern KEYWORD_PATTERN = Pattern.compile("[가-힣a-zA-Z0-9]{2,}");

	private final EmbeddingProvider embeddingProvider;
	private final QueryTransformer queryTransformer;
	private final FaqRetriever faqRetriever;
	private final PlanSearchService planSearchService;
	private final RetrievalSettings settings;

	public RetrievalPipeline(EmbeddingProvider embeddingProvider, QueryTransformer queryTransformer,
			FaqRetriever faqRetriever, PlanSearchService planSearchService, RetrievalSettings settings) {
		this.embeddingProvider = embeddingProvider;
		this.queryTransformer = queryTransformer;
		this.faqRetriever = faqRetriever;
		this.planSearchService = planSearchService;
		this.settings = settings;
	}

	/** 설정값(threshold, 가산점 등). 평가 러너가 리포트에 같은 값을 표시하는 데 쓴다. */
	public RetrievalSettings settings() {
		return settings;
	}

	/**
	 * 질문 변환기와 FAQ 검색기만 바꾼 같은 설정의 파이프라인을 만든다. 평가 러너가 실험 변형(영어, 질문 변환, Hybrid)을
	 * 이 방법으로 갈아끼운다. 서비스 빈은 바뀌지 않는다.
	 */
	public RetrievalPipeline with(QueryTransformer transformer, FaqRetriever retriever) {
		return new RetrievalPipeline(embeddingProvider, transformer, retriever, planSearchService, settings);
	}

	/** 실제 서비스와 같은 옵션으로 검색한다. */
	public RetrievalResult run(String query, int topK) {
		return run(query, RetrievalOptions.forService(topK));
	}

	/**
	 * 검색 한 번을 실행한다.
	 *
	 * @param query   사용자 질문 원문
	 * @param options topK, 후보 풀 크기, 요금제 검색 여부, 로그 여부
	 */
	public RetrievalResult run(String query, RetrievalOptions options) {
		LapClock clock = new LapClock();

		// 1. 질문 변환
		TransformedQuery transformed = queryTransformer.transform(query);
		long transformNanos = clock.lap();

		// 2. 임베딩. FAQ와 요금제는 같은 임베딩 모델·차원이라, 두 질문이 같으면 벡터 변환을 한 번만 한다.
		float[] faqVector = embeddingProvider.embedQuery(transformed.faqQuery());
		float[] planVector = faqVector;
		if (options.includePlans() && !transformed.sameForFaqAndPlan()) {
			planVector = embeddingProvider.embedQuery(transformed.planQuery());
		}
		long embeddingNanos = clock.lap();

		// 3. FAQ 후보 검색. threshold 미달 후보의 최고 점수도 topSimilarity로 알려야 해서, 검색기는 threshold 없이 후보를
		// 가까운 순으로 가져오고 threshold는 후처리에서 적용한다(정렬이 유사도 순이라 결과 집합은 동일).
		List<FaqSimilarityResult> pool = faqRetriever.retrieve(new RetrievalQuery(transformed.faqQuery(), faqVector), options.poolSize());
		long faqSearchNanos = clock.lap();

		// 4. 요금제 검색. FAQ와 각각(별도 쿼리) 조회 후 병합한다 — UNION 한 쿼리 대신 이 방식을 택한 이유는 두 테이블의 유사도
		// 분포가 달라(threshold도 다름) 한 번에 정렬·컷오프하면 한쪽이 불리해질 수 있어서다. 요금제는 벡터 유사도에 더해,
		// 질문의 가격·데이터량·대상 그룹·무제한 여부를 plans 컬럼과 직접 비교한다.
		PlanSearchService.PlanSearchOutcome planOutcome = options.includePlans()
				? planSearchService.search(transformed.planQuery(), planVector, options.topK())
				: new PlanSearchService.PlanSearchOutcome(List.of(), false);
		// 요금제를 검색하지 않았으면(FAQ만 평가) 이 단계 시간은 0으로 둔다.
		long planLapNanos = clock.lap();
		long planSearchNanos = options.includePlans() ? planLapNanos : 0L;

		// 5. Context 구성
		// 개인 정보 조회·타사 질문처럼 FAQ로 답할 수 없는 유형은 문장 모양(규칙)으로 알아본다. 변환된 질문이 아니라 원문으로 판정한다.
		Set<IrrelevantQueryDetector.Rule> irrelevantRules =
				settings.irrelevantRuleEnabled() ? IrrelevantQueryDetector.detect(query) : Set.of();

		FaqSelection faq = selectFaq(query, pool, options.topK());

		if (options.logDetails()) {
			if (faq.results().isEmpty()) {
				log.info("관련 FAQ 없음 (threshold={}, 최고 유사도={}). query={}",
						settings.faqThreshold(), String.format("%.4f", faq.topSimilarity()), query);
			} else if (irrelevantRules.isEmpty()) {
				logRankingSignals(query, faq.results());
			}
		}

		List<PlanSimilarityResult> planCandidates = planOutcome.results();
		double planTopSimilarity = planCandidates.isEmpty() ? 0.0 : planCandidates.get(0).similarity();

		// 규칙에 걸린 질문은 FAQ·요금제를 모두 비워 BE4에 "관련 없음"으로 전달한다. 개인 정보 질문에는 조회할 실제 데이터가 없고,
		// 타사 비교 질문에 우리 요금제를 주면 LLM이 타사 정보를 지어낼 위험이 있다. topSimilarity는 BE4의 분석에 쓰이므로
		// 인위적으로 바꾸지 않고 실제 최고 유사도를 그대로 전달한다. 규칙이 잘못 막는 사례를 실사용 로그로 확인할 수 있도록
		// 질문과 규칙 이름을 남긴다.
		if (!irrelevantRules.isEmpty()) {
			double blockedTop = Math.max(faq.topSimilarity(), planTopSimilarity);
			if (options.logDetails()) {
				log.info("무관 질문 규칙 판정으로 FAQ·요금제 컨텍스트 제외: rules={}, FAQ 최고 유사도={}, 요금제 최고 유사도={}. query={}",
						irrelevantRules, String.format("%.4f", faq.topSimilarity()), String.format("%.4f", planTopSimilarity), query);
			}
			FaqRetrievalContext blocked = new FaqRetrievalContext(List.of(), List.of(), blockedTop);
			StageTimings blockedTimings = new StageTimings(transformNanos, embeddingNanos, faqSearchNanos, planSearchNanos, clock.lap());
			return finish(transformed, faq, planOutcome, List.of(), planTopSimilarity, blockedTop, irrelevantRules,
					blockedTimings, blocked, options);
		}

		// 조건으로 좁혀진 결과는 "3만1천원"처럼 임베딩 유사도가 낮게 나오는 질문도 포함하므로 threshold를 적용하지 않는다.
		List<PlanSimilarityResult> planResults = planOutcome.conditionMatched()
				? planCandidates
				: planCandidates.stream()
						.filter(candidate -> candidate.similarity() >= settings.planThreshold())
						.toList();

		if (options.includePlans()) {
			if (planResults.isEmpty()) {
				if (options.logDetails()) {
					log.info("관련 요금제 없음 (threshold={}, 최고 유사도={}). query={}",
							settings.planThreshold(), String.format("%.4f", planTopSimilarity), query);
				}
			} else if (planOutcome.conditionMatched()) {
				if (options.logDetails()) {
					log.info("요금제 조건 매칭: query={} → {}", query,
							planResults.stream().map(PlanSimilarityResult::planCode).toList());
				}
				// 조건이 맞은 요금제는 확실한 근거라, BE4의 LOW_CONFIDENCE 판단에서 낮은 유사도로 밀리지 않도록 threshold 이상으로 올린다.
				planTopSimilarity = Math.max(planTopSimilarity, settings.planThreshold());
			}
		}

		double topSimilarity = Math.max(faq.topSimilarity(), planTopSimilarity);
		FaqRetrievalContext context = new FaqRetrievalContext(
				faq.results().stream().map(RetrievalPipeline::toReference).toList(),
				planResults.stream().map(RetrievalPipeline::toPlanReference).toList(),
				topSimilarity);

		StageTimings timings = new StageTimings(transformNanos, embeddingNanos, faqSearchNanos, planSearchNanos, clock.lap());
		return finish(transformed, faq, planOutcome, planResults, planTopSimilarity, topSimilarity, irrelevantRules,
				timings, context, options);
	}

	/**
	 * 후보 풀에서 최종 FAQ를 고르는 공통 후처리: 분류 이름 가산점 재정렬 → 같은 답변 중복 제거 → topK → threshold.
	 * 평가 러너가 풀 크기를 바꿔 가며(풀의 앞부분만 잘라서) 비교할 때도 이 메소드를 그대로 부른다.
	 *
	 * @param originalQuery 사용자 질문 원문(분류 이름 가산점이 한국어 기준이라 변환 전 질문을 쓴다)
	 * @param pool          검색기가 돌려준 후보 풀(순위 순)
	 * @param topK          최종 결과 최대 개수
	 */
	public FaqSelection selectFaq(String originalQuery, List<FaqSimilarityResult> pool, int topK) {
		// 질문에 분류 이름 단어가 있으면 그 분류를 약간 앞세워 순위를 다시 매긴다(유사도 값은 그대로).
		List<FaqSimilarityResult> ranked = FaqCategoryTermBooster.rerank(originalQuery, pool, settings.categoryBoostBonus());
		// 같은 답변의 변형이 topK를 다 차지하지 않도록, 후보를 넉넉히 가져온 뒤 중복을 걷어내고 topK개로 자른다.
		List<FaqSimilarityResult> candidates = FaqCandidateSelector.selectDistinct(ranked, topK);
		// BE4에 알리는 최고 유사도는 순위와 상관없이 후보의 원래 최고값이다(재정렬로 1등이 바뀌어도 값이 달라지지 않는다).
		double topSimilarity = pool.stream().mapToDouble(FaqSimilarityResult::similarity).max().orElse(0.0);
		List<FaqSimilarityResult> results = candidates.stream()
				.filter(candidate -> candidate.similarity() >= settings.faqThreshold())
				.toList();
		return new FaqSelection(pool, ranked, candidates, results, topSimilarity);
	}

	/** 단계별 시간을 로그로 남기고(옵션) 결과를 묶는다. */
	private RetrievalResult finish(TransformedQuery transformed, FaqSelection faq,
			PlanSearchService.PlanSearchOutcome planOutcome, List<PlanSimilarityResult> planResults,
			double planTopSimilarity, double topSimilarity, Set<IrrelevantQueryDetector.Rule> irrelevantRules,
			StageTimings timings, FaqRetrievalContext context, RetrievalOptions options) {
		if (options.logDetails()) {
			log.info("검색 단계별 시간: {}", timings.summary());
		}
		return new RetrievalResult(transformed, faq, planOutcome, planResults, planTopSimilarity, topSimilarity,
				irrelevantRules, timings, context);
	}

	/**
	 * threshold 하나만으로 충분한지 판단하기 위한 관찰용 로그. 아직 실제 필터링에는 쓰지 않는다 — top1-top2 유사도 격차를
	 * 실제로 측정해보니, 정답 FAQ가 2개 이상 겹치는 질문에서는 격차가 무관한 질문만큼 작게 나오는 경우가 있어서(예: "로밍 요금제
	 * 시작 시간" 질문에서 0.9209/0.9168), 격차만으로 걸러내면 진짜 정답을 오답 처리할 위험이 있었다. 키워드 겹침도 같은
	 * 이유로 데이터가 더 쌓일 때까지는 로그만 남기고 필터링에는 반영하지 않는다.
	 */
	private void logRankingSignals(String query, List<FaqSimilarityResult> results) {
		Set<String> queryKeywords = extractKeywords(query);

		FaqSimilarityResult top1 = results.get(0);
		String gap = results.size() >= 2
				? String.format("%.4f", top1.similarity() - results.get(1).similarity())
				: "N/A(top1뿐)";

		log.info("검색 순위 신호(관찰용, 미필터링): query=\"{}\" top1-top2 격차={}", query, gap);
		for (int i = 0; i < results.size(); i++) {
			FaqSimilarityResult r = results.get(i);
			int overlap = countKeywordOverlap(queryKeywords, r);
			log.info("  #{} similarity={} keywordOverlap={} question={}",
					i + 1, String.format("%.4f", r.similarity()), overlap, r.question());
		}
	}

	private Set<String> extractKeywords(String text) {
		return KEYWORD_PATTERN.matcher(text).results()
				.map(m -> m.group())
				.collect(Collectors.toSet());
	}

	/** 질문 키워드가 후보 FAQ의 category/subcategory/question/answer에 몇 개나 등장하는지(부분 문자열 기준). */
	private int countKeywordOverlap(Set<String> queryKeywords, FaqSimilarityResult candidate) {
		String haystack = candidate.category() + " " + candidate.subcategory() + " "
				+ candidate.question() + " " + candidate.answer();
		return (int) queryKeywords.stream().filter(haystack::contains).count();
	}

	/** 직전 측정 이후 흐른 시간을 나노초로 돌려주는 단계별 시계. 한 번의 검색 안에서만 쓴다. */
	private static final class LapClock {

		private long mark = System.nanoTime();

		/** 직전 lap() 이후(처음에는 생성 이후) 흐른 나노초를 돌려주고 기준 시각을 지금으로 옮긴다. */
		long lap() {
			long now = System.nanoTime();
			long elapsed = now - mark;
			mark = now;
			return elapsed;
		}
	}

	/** FaqSimilarityResult(내부 검색 결과) → FaqReference(BE4/FE1 대외 계약) 변환. */
	private static FaqReference toReference(FaqSimilarityResult result) {
		return new FaqReference(
				result.id(),
				result.category(),
				result.subcategory(),
				result.question(),
				result.answer(),
				result.similarity(),
				result.updatedAt());
	}

	/** PlanSimilarityResult(내부 검색 결과) → PlanReference(BE4/FE1 대외 계약) 변환. */
	private static PlanReference toPlanReference(PlanSimilarityResult result) {
		return new PlanReference(
				result.id(),
				result.planCode(),
				result.name(),
				result.summary(),
				result.monthlyFee(),
				result.description(),
				result.similarity(),
				result.updatedAt(),
				result.networkType(),
				result.targetGroup(),
				result.minAge(),
				result.maxAge(),
				result.dataPolicy(),
				result.baseDataMb(),
				result.exhaustedSpeedKbps(),
				result.voicePolicy(),
				result.voiceMinutes(),
				result.smsPolicy(),
				result.smsCount());
	}
}
