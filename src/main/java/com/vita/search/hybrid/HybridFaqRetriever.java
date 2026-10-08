package com.vita.search.hybrid;

import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.pipeline.FaqRetriever;
import com.vita.search.pipeline.RetrievalQuery;
import com.vita.search.repository.FaqVectorSearchRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FAQ Hybrid 후보 검색기. 한국어 컬럼의 벡터 검색과 키워드(BM25) 검색을 가중 RRF로 합친다(기본은 벡터 쪽 가중).
 * {@code search.pipeline.faq-retriever}(서비스) 또는 {@code search.eval.faq-retriever}(평가)에 {@value #BEAN_NAME}을 적어서 끼운다.
 *
 * <p>{@link FaqRetriever}의 약속을 지킨다: 후보 순서는 RRF 최종 순위, {@code similarity}는 코사인 유사도, {@code id}는 원본 FAQ id.
 * 키워드로 쓸 텍스트가 비면(공백 질문) 벡터 검색과 같은 결과를 돌려준다.
 */
@Component(HybridFaqRetriever.BEAN_NAME)
@ConditionalOnProperty(prefix = "search.hybrid", name = "enabled", havingValue = "true")
public class HybridFaqRetriever implements FaqRetriever {

	/** 설정에서 이 검색기를 가리킬 때 쓰는 빈 이름. */
	public static final String BEAN_NAME = "hybridFaqRetriever";

	private final FaqHybridSearchRepository hybridRepository;
	private final FaqVectorSearchRepository vectorRepository;
	private final HybridProperties.Side settings;

	public HybridFaqRetriever(FaqHybridSearchRepository hybridRepository, FaqVectorSearchRepository vectorRepository,
			HybridProperties properties) {
		this.hybridRepository = hybridRepository;
		this.vectorRepository = vectorRepository;
		this.settings = properties.faq();
	}

	@Override
	public List<FaqSimilarityResult> retrieve(RetrievalQuery query, int poolSize) {
		String keywordText = Bm25QueryText.from(query.text());
		if (keywordText.isEmpty()) {
			return vectorRepository.searchBySimilarity(query.vector(), FaqStatus.ACTIVE, 0.0, poolSize);
		}
		return hybridRepository.search(query.vector(), keywordText, settings.weights(), settings.candidateLimitFor(poolSize), poolSize);
	}
}
