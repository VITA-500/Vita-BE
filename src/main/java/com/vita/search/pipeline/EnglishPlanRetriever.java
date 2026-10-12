package com.vita.search.pipeline;

import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.repository.EnglishPlanVectorSearchRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 영어 요금제 후보 검색기(실험 B). 영어 임베딩(plans_en)에서 코사인 유사도가 높은 순으로 후보를 가져오되, 후보의 id와
 * 속성은 원본 요금제(plans)의 값으로 돌려준다. threshold는 {@link VectorPlanRetriever}와 같이 여기서 걸지 않는다.
 */
@Component(EnglishPlanRetriever.BEAN_NAME)
public class EnglishPlanRetriever implements PlanRetriever {

	/** 설정({@code search.pipeline.plan-retriever} 등)에서 이 검색기를 가리킬 때 쓰는 빈 이름. */
	public static final String BEAN_NAME = "englishPlanRetriever";

	private final EnglishPlanVectorSearchRepository englishPlanVectorSearchRepository;

	public EnglishPlanRetriever(EnglishPlanVectorSearchRepository englishPlanVectorSearchRepository) {
		this.englishPlanVectorSearchRepository = englishPlanVectorSearchRepository;
	}

	@Override
	public List<PlanSimilarityResult> retrieve(RetrievalQuery query, int poolSize) {
		return englishPlanVectorSearchRepository.searchBySimilarity(query.vector(), 0.0, poolSize);
	}
}
