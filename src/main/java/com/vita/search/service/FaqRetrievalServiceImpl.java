package com.vita.search.service;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanReference;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqVectorSearchRepository;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * {@link FaqRetrievalService}의 실제 구현. 사용자 질문을 BE2의 {@link EmbeddingProvider}로
 * 벡터화한 뒤 {@link FaqVectorSearchRepository}로 유사도 검색을 수행한다.
 */
@Slf4j
@Service
public class FaqRetrievalServiceImpl implements FaqRetrievalService {

	/**
	 * "관련 FAQ 없음"으로 처리할 유사도 하한선. BE2가 카테고리(로밍/요금·납부/유심-eSIM)를
	 * 늘려준 데이터로 재측정해서 0.85 → 0.83으로 낮췄다. 단순히 낮추기만 하면 안 되는 이유가
	 * 있었다 — "심카드를 새로 받아야 하는데..."(유심 질문, "유심" 단어 회피) 같은 케이스에서
	 * top1이 엉뚱하게 요금/납부 카테고리 FAQ로 0.8179가 나왔다("카드"라는 글자가 "신용카드"와
	 * 겹쳐서로 추정). 0.83은 로밍/요금 파라프레이즈 정답(0.8423/0.8547)은 통과시키면서, 이
	 * 잘못된 카테고리 매칭(0.8179)과 완전 무관한 질문(~0.80)은 여전히 걸러내는 값이다.
	 */
	@Value("${retrieval.similarity-threshold:0.83}")
	private double similarityThreshold;

	/**
	 * "관련 요금제 없음"으로 처리할 유사도 하한선. 요금제는 질문/답변이 아니라 설명 문장(description)
	 * 형태라 FAQ와 유사도 분포가 달라 별도 값으로 둔다. 요금제 15종 실측 기준 — 타겟 그룹이 맞는
	 * 질문(청년/시니어/키즈/워치 등)의 top1은 0.8208~0.8767, FAQ성·무관 질문의 top1은 0.7414~0.8008
	 * 이었다. 그 사이인 0.81로 잡았다.
	 */
	@Value("${plan.retrieval.similarity-threshold:0.81}")
	private double planSimilarityThreshold;

	/**
	 * 규칙(정규식)으로 개인 정보 조회·타사 질문을 미리 알아보고, 걸리면 FAQ·요금제 컨텍스트를 비울지 여부.
	 * 회귀 측정에서 정상 질문 오탐 0건(FAQ DB 1,529건 등 1,863개)에 threshold를 넘어 새던 무관 질문의
	 * 절반 이상(45~79%)을 막았다. 실사용에서 잘못 막는 사례가 보이면 이 값을 false로 꺼서 즉시 되돌릴 수 있다.
	 */
	@Value("${retrieval.irrelevant-rule.enabled:true}")
	private boolean irrelevantRuleEnabled;

	/**
	 * 질문에 분류 이름 단어(IPTV, 유선, 소상공인, 유심 등)가 있을 때 그 분류의 FAQ 후보에 더하는 순위용 가산점.
	 * FAQ가 같은 문장 틀에 상품 이름만 바꿔 만든 구조라, 오타·구어체 질문에서 상품 단어를 약하게 반영하면 이웃 상품의
	 * FAQ가 1등이 되곤 했다. 회귀 측정에서 0.01은 FAQ Top-1을 85.7% → 88.7%로 올리면서 망가지는 질문이 없었고,
	 * 규칙을 만들 때 보지 않은 검증 질문 43개에서도 해가 없었다(0.02 이상은 일부 망가짐). 순위에만 쓰이고 유사도 값과
	 * threshold 판단은 바뀌지 않는다. 0으로 두면 꺼진다.
	 */
	@Value("${retrieval.category-boost.bonus:0.01}")
	private double categoryBoostBonus;

	/** 한글/영문/숫자가 2자 이상 연속된 덩어리만 키워드로 취급 (조사 등 형태소 분리는 안 함 — 근사치). */
	private static final Pattern KEYWORD_PATTERN = Pattern.compile("[가-힣a-zA-Z0-9]{2,}");

	private final EmbeddingProvider embeddingProvider;
	private final FaqVectorSearchRepository faqVectorSearchRepository;
	private final PlanSearchService planSearchService;

	public FaqRetrievalServiceImpl(
			EmbeddingProvider embeddingProvider,
			FaqVectorSearchRepository faqVectorSearchRepository,
			PlanSearchService planSearchService) {
		this.embeddingProvider = embeddingProvider;
		this.faqVectorSearchRepository = faqVectorSearchRepository;
		this.planSearchService = planSearchService;
	}

	@Override
	public FaqRetrievalContext search(String query, int topK) {
		// FAQ와 요금제는 같은 임베딩 모델·차원이라 벡터 변환은 한 번만 하고 두 테이블에 그대로 쓴다.
		float[] queryVector = embeddingProvider.embedQuery(query);

		// 개인 정보 조회·타사 질문처럼 FAQ로 답할 수 없는 유형은 문장 모양(규칙)으로 미리 알아본다.
		Set<IrrelevantQueryDetector.Rule> irrelevantRules =
				irrelevantRuleEnabled ? IrrelevantQueryDetector.detect(query) : Set.of();

		// threshold 미달 후보의 최고 점수도 topSimilarity로 알려야 해서, DB에서는 threshold 없이
		// 가까운 순으로 가져오고 threshold는 아래에서 적용한다(정렬이 유사도 순이라 결과 집합은 동일).
		// 같은 답변의 변형이 topK를 다 차지하지 않도록, topK보다 넉넉히 가져와 중복을 걷어낸 뒤 자른다.
		List<FaqSimilarityResult> faqPool = faqVectorSearchRepository.searchBySimilarity(
				queryVector, FaqStatus.ACTIVE, 0.0, FaqCandidateSelector.poolSize(topK));
		// 질문에 분류 이름 단어가 있으면 그 분류를 약간 앞세워 순위를 다시 매긴다(유사도 값은 그대로).
		List<FaqSimilarityResult> rankedPool = FaqCategoryTermBooster.rerank(query, faqPool, categoryBoostBonus);
		List<FaqSimilarityResult> faqCandidates = FaqCandidateSelector.selectDistinct(rankedPool, topK);
		// BE4에 알리는 최고 유사도는 순위와 상관없이 후보의 원래 최고값이다(재정렬로 1등이 바뀌어도 값이 달라지지 않는다).
		double faqTopSimilarity = faqPool.stream().mapToDouble(FaqSimilarityResult::similarity).max().orElse(0.0);

		List<FaqSimilarityResult> faqResults = faqCandidates.stream()
				.filter(candidate -> candidate.similarity() >= similarityThreshold)
				.toList();

		if (faqResults.isEmpty()) {
			log.info("관련 FAQ 없음 (threshold={}, 최고 유사도={}). query={}",
					similarityThreshold, String.format("%.4f", faqTopSimilarity), query);
		} else if (irrelevantRules.isEmpty()) {
			logRankingSignals(query, faqResults);
		}

		// FAQ와 각각(별도 쿼리) 조회 후 병합한다 — UNION 한 쿼리 대신 이 방식을 택한 이유는
		// 두 테이블의 유사도 분포가 달라(threshold도 다름) 한 번에 정렬·컷오프하면 한쪽이
		// 불리해질 수 있어서다. 요금제 15종 규모라 쿼리 하나 더 도는 비용은 무시할 만하다.
		// 요금제는 벡터 유사도에 더해, 질문의 가격·데이터량·대상 그룹·무제한 여부를 plans 컬럼과 직접 비교한다.
		PlanSearchService.PlanSearchOutcome planOutcome = planSearchService.search(query, queryVector, topK);
		List<PlanSimilarityResult> planCandidates = planOutcome.results();
		double planTopSimilarity = planCandidates.isEmpty() ? 0.0 : planCandidates.get(0).similarity();

		// 규칙에 걸린 질문은 FAQ·요금제를 모두 비워 BE4에 "관련 없음"으로 전달한다. 개인 정보 질문에는 조회할
		// 실제 데이터가 없고, 타사 비교 질문에 우리 요금제를 주면 LLM이 타사 정보를 지어낼 위험이 있다.
		// topSimilarity는 BE4의 분석에 쓰이므로 인위적으로 바꾸지 않고 실제 최고 유사도를 그대로 전달한다.
		// 규칙이 잘못 막는 사례를 실사용 로그로 확인할 수 있도록 질문과 규칙 이름을 남긴다.
		if (!irrelevantRules.isEmpty()) {
			log.info("무관 질문 규칙 판정으로 FAQ·요금제 컨텍스트 제외: rules={}, FAQ 최고 유사도={}, 요금제 최고 유사도={}. query={}",
					irrelevantRules, String.format("%.4f", faqTopSimilarity), String.format("%.4f", planTopSimilarity), query);
			return new FaqRetrievalContext(List.of(), List.of(), Math.max(faqTopSimilarity, planTopSimilarity));
		}

		// 조건으로 좁혀진 결과는 "3만1천원"처럼 임베딩 유사도가 낮게 나오는 질문도 포함하므로 threshold를 적용하지 않는다.
		List<PlanSimilarityResult> planResults = planOutcome.conditionMatched()
				? planCandidates
				: planCandidates.stream()
						.filter(candidate -> candidate.similarity() >= planSimilarityThreshold)
						.toList();

		if (planResults.isEmpty()) {
			log.info("관련 요금제 없음 (threshold={}, 최고 유사도={}). query={}",
					planSimilarityThreshold, String.format("%.4f", planTopSimilarity), query);
		} else if (planOutcome.conditionMatched()) {
			log.info("요금제 조건 매칭: query={} → {}", query,
					planResults.stream().map(PlanSimilarityResult::planCode).toList());
			// 조건이 맞은 요금제는 확실한 근거라, BE4의 LOW_CONFIDENCE 판단에서 낮은 유사도로 밀리지 않도록 threshold 이상으로 올린다.
			planTopSimilarity = Math.max(planTopSimilarity, planSimilarityThreshold);
		}

		double topSimilarity = Math.max(faqTopSimilarity, planTopSimilarity);

		return new FaqRetrievalContext(
				faqResults.stream().map(this::toReference).toList(),
				planResults.stream().map(this::toPlanReference).toList(),
				topSimilarity);
	}

	/**
	 * threshold 하나만으로 충분한지 판단하기 위한 관찰용 로그. 아직 실제 필터링에는 쓰지 않는다 —
	 * top1-top2 유사도 격차를 실제로 측정해보니, 정답 FAQ가 2개 이상 겹치는 질문에서는
	 * 격차가 무관한 질문만큼 작게 나오는 경우가 있어서(예: "로밍 요금제 시작 시간" 질문에서
	 * 0.9209/0.9168), 격차만으로 걸러내면 진짜 정답을 오답 처리할 위험이 있었다. 키워드 겹침도
	 * 같은 이유로 데이터가 더 쌓일 때까지는 로그만 남기고 필터링에는 반영하지 않는다.
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

	/** FaqSimilarityResult(내부 검색 결과) → FaqReference(BE4/FE1 대외 계약) 변환. */
	private FaqReference toReference(FaqSimilarityResult result) {
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
	private PlanReference toPlanReference(PlanSimilarityResult result) {
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
