package com.vita.search.pipeline;

import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.EnglishFaqVectorSearchRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 영어 FAQ 후보 검색기(실험 B). 영어 임베딩(faqs_en)에서 코사인 유사도가 높은 순으로 후보를 가져오되, 후보의 id와 본문은
 * 원본 한국어 FAQ(faqs)의 값으로 돌려준다. 질문도 영어여야 하므로 영어 번역 변환기({@code translatingQueryTransformer})와
 * 함께 쓴다. threshold는 {@link VectorFaqRetriever}와 같이 여기서 걸지 않는다.
 */
@Component(EnglishFaqRetriever.BEAN_NAME)
public class EnglishFaqRetriever implements FaqRetriever {

	/** 설정({@code search.pipeline.faq-retriever} 등)에서 이 검색기를 가리킬 때 쓰는 빈 이름. */
	public static final String BEAN_NAME = "englishFaqRetriever";

	private final EnglishFaqVectorSearchRepository englishFaqVectorSearchRepository;

	public EnglishFaqRetriever(EnglishFaqVectorSearchRepository englishFaqVectorSearchRepository) {
		this.englishFaqVectorSearchRepository = englishFaqVectorSearchRepository;
	}

	@Override
	public List<FaqSimilarityResult> retrieve(RetrievalQuery query, int poolSize) {
		return englishFaqVectorSearchRepository.searchBySimilarity(query.vector(), FaqStatus.ACTIVE, 0.0, poolSize);
	}
}
