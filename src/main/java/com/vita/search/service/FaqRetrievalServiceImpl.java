package com.vita.search.service;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.FaqSimilarityResult;
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

	/** 한글/영문/숫자가 2자 이상 연속된 덩어리만 키워드로 취급 (조사 등 형태소 분리는 안 함 — 근사치). */
	private static final Pattern KEYWORD_PATTERN = Pattern.compile("[가-힣a-zA-Z0-9]{2,}");

	private final EmbeddingProvider embeddingProvider;
	private final FaqVectorSearchRepository faqVectorSearchRepository;

	public FaqRetrievalServiceImpl(EmbeddingProvider embeddingProvider, FaqVectorSearchRepository faqVectorSearchRepository) {
		this.embeddingProvider = embeddingProvider;
		this.faqVectorSearchRepository = faqVectorSearchRepository;
	}

	@Override
	public FaqRetrievalContext search(String query, int topK) {
		float[] queryVector = embeddingProvider.embedQuery(query);

		// threshold 미달 후보의 최고 점수도 topSimilarity로 알려야 해서, DB에서는 threshold 없이
		// 가까운 순 topK를 가져오고 threshold는 아래에서 적용한다(정렬이 유사도 순이라 결과 집합은 동일).
		List<FaqSimilarityResult> candidates = faqVectorSearchRepository.searchBySimilarity(
				queryVector, FaqStatus.ACTIVE, 0.0, topK);
		double topSimilarity = candidates.isEmpty() ? 0.0 : candidates.get(0).similarity();

		List<FaqSimilarityResult> results = candidates.stream()
				.filter(candidate -> candidate.similarity() >= similarityThreshold)
				.toList();

		if (results.isEmpty()) {
			log.info("관련 FAQ 없음 (threshold={}, 최고 유사도={}). query={}",
					similarityThreshold, String.format("%.4f", topSimilarity), query);
		} else {
			logRankingSignals(query, results);
		}

		return new FaqRetrievalContext(results.stream().map(this::toReference).toList(), topSimilarity);
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
}
