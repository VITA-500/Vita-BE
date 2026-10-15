package com.vita.search.pipeline;

import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.repository.PlanVectorSearchRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 기본 요금제 후보 검색기. ACTIVE 요금제의 임베딩 컬럼에서 코사인 유사도가 높은 순으로 후보를 가져온다.
 * threshold는 여기서 걸지 않는다(요금제 검색이 조건 매칭 여부에 따라 따로 판정한다).
 */
@Component(VectorPlanRetriever.BEAN_NAME)
public class VectorPlanRetriever implements PlanRetriever {

	/** 설정({@code search.pipeline.plan-retriever} 등)에서 이 검색기를 가리킬 때 쓰는 빈 이름. */
	public static final String BEAN_NAME = "vectorPlanRetriever";

	private final PlanVectorSearchRepository planVectorSearchRepository;

	public VectorPlanRetriever(PlanVectorSearchRepository planVectorSearchRepository) {
		this.planVectorSearchRepository = planVectorSearchRepository;
	}

	@Override
	public List<PlanSimilarityResult> retrieve(RetrievalQuery query, int poolSize) {
		return planVectorSearchRepository.searchBySimilarity(query.vector(), 0.0, poolSize);
	}
}
