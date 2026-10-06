package com.vita.search.pipeline;

import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqVectorSearchRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 기본 후보 검색기. ACTIVE FAQ의 한국어 임베딩 컬럼에서 코사인 유사도가 높은 순으로 후보를 가져온다.
 * threshold는 여기서 걸지 않는다(threshold 미달 후보의 최고 점수도 BE4에 알려야 해서, 후처리에서 판정한다).
 */
@Component(VectorFaqRetriever.BEAN_NAME)
public class VectorFaqRetriever implements FaqRetriever {

	/** 설정({@code search.pipeline.faq-retriever} 등)에서 이 검색기를 가리킬 때 쓰는 빈 이름. */
	public static final String BEAN_NAME = "vectorFaqRetriever";

	private final FaqVectorSearchRepository faqVectorSearchRepository;

	public VectorFaqRetriever(FaqVectorSearchRepository faqVectorSearchRepository) {
		this.faqVectorSearchRepository = faqVectorSearchRepository;
	}

	@Override
	public List<FaqSimilarityResult> retrieve(RetrievalQuery query, int poolSize) {
		return faqVectorSearchRepository.searchBySimilarity(query.vector(), FaqStatus.ACTIVE, 0.0, poolSize);
	}
}
