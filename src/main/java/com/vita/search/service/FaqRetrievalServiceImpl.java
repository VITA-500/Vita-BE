package com.vita.search.service;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.dto.PlanReference;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqVectorSearchRepository;
import com.vita.search.repository.PlanVectorSearchRepository;
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

	/** 한글/영문/숫자가 2자 이상 연속된 덩어리만 키워드로 취급 (조사 등 형태소 분리는 안 함 — 근사치). */
	private static final Pattern KEYWORD_PATTERN = Pattern.compile("[가-힣a-zA-Z0-9]{2,}");

	private final EmbeddingProvider embeddingProvider;
	private final FaqVectorSearchRepository faqVectorSearchRepository;
	private final PlanVectorSearchRepository planVectorSearchRepository;

	public FaqRetrievalServiceImpl(
			EmbeddingProvider embeddingProvider,
			FaqVectorSearchRepository faqVectorSearchRepository,
			PlanVectorSearchRepository planVectorSearchRepository) {
		this.embeddingProvider = embeddingProvider;
		this.faqVectorSearchRepository = faqVectorSearchRepository;
		this.planVectorSearchRepository = planVectorSearchRepository;
	}

	@Override
	public FaqRetrievalContext search(String query, int topK) {
		// FAQ와 요금제는 같은 임베딩 모델·차원이라 벡터 변환은 한 번만 하고 두 테이블에 그대로 쓴다.
		float[] queryVector = embeddingProvider.embedQuery(query);

		// threshold 미달 후보의 최고 점수도 topSimilarity로 알려야 해서, DB에서는 threshold 없이
		// 가까운 순 topK를 가져오고 threshold는 아래에서 적용한다(정렬이 유사도 순이라 결과 집합은 동일).
		List<FaqSimilarityResult> faqCandidates = faqVectorSearchRepository.searchBySimilarity(
				queryVector, FaqStatus.ACTIVE, 0.0, topK);
		double faqTopSimilarity = faqCandidates.isEmpty() ? 0.0 : faqCandidates.get(0).similarity();

		List<FaqSimilarityResult> faqResults = faqCandidates.stream()
				.filter(candidate -> candidate.similarity() >= similarityThreshold)
				.toList();

		if (faqResults.isEmpty()) {
			log.info("관련 FAQ 없음 (threshold={}, 최고 유사도={}). query={}",
					similarityThreshold, String.format("%.4f", faqTopSimilarity), query);
		} else {
			logRankingSignals(query, faqResults);
		}

		// FAQ와 각각(별도 쿼리) 조회 후 병합한다 — UNION 한 쿼리 대신 이 방식을 택한 이유는
		// 두 테이블의 유사도 분포가 달라(threshold도 다름) 한 번에 정렬·컷오프하면 한쪽이
		// 불리해질 수 있어서다. 요금제 15종 규모라 쿼리 하나 더 도는 비용은 무시할 만하다.
		List<PlanSimilarityResult> planCandidates = planVectorSearchRepository.searchBySimilarity(
				queryVector, 0.0, topK);
		double planTopSimilarity = planCandidates.isEmpty() ? 0.0 : planCandidates.get(0).similarity();

		List<PlanSimilarityResult> planResults = planCandidates.stream()
				.filter(candidate -> candidate.similarity() >= planSimilarityThreshold)
				.toList();

		if (planResults.isEmpty()) {
			log.info("관련 요금제 없음 (threshold={}, 최고 유사도={}). query={}",
					planSimilarityThreshold, String.format("%.4f", planTopSimilarity), query);
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
				result.updatedAt());
	}
}
