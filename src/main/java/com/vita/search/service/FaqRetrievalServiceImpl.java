package com.vita.search.service;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqVectorSearchRepository;
import java.util.List;
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
	 * "관련 FAQ 없음"으로 처리할 유사도 하한선. 0.85는 BE2와 협의 전 임시값이다 —
	 * multilingual-e5-base로 무관한 질문을 넣어봐도 ~0.79까지 나오는 걸 확인해서,
	 * 관련/무관 구간이 촘촘하다. BE2 피드백을 받으면 재조정해야 한다.
	 */
	@Value("${retrieval.similarity-threshold:0.85}")
	private double similarityThreshold;

	private final EmbeddingProvider embeddingProvider;
	private final FaqVectorSearchRepository faqVectorSearchRepository;

	public FaqRetrievalServiceImpl(EmbeddingProvider embeddingProvider, FaqVectorSearchRepository faqVectorSearchRepository) {
		this.embeddingProvider = embeddingProvider;
		this.faqVectorSearchRepository = faqVectorSearchRepository;
	}

	@Override
	public FaqRetrievalContext search(String query, int topK) {
		float[] queryVector = embeddingProvider.embedQuery(query);

		List<FaqSimilarityResult> results = faqVectorSearchRepository.searchBySimilarity(
				queryVector, FaqStatus.ACTIVE, similarityThreshold, topK);

		if (results.isEmpty()) {
			log.info("관련 FAQ 없음 (threshold={} 미달 또는 결과 없음). query={}", similarityThreshold, query);
		}

		return new FaqRetrievalContext(results.stream().map(this::toReference).toList());
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
