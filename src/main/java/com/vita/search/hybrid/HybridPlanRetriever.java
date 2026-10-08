package com.vita.search.hybrid;

import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.pipeline.PlanRetriever;
import com.vita.search.pipeline.RetrievalQuery;
import com.vita.search.repository.PlanVectorSearchRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 요금제 Hybrid 후보 검색기. 벡터 검색과 키워드(BM25) 검색을 가중 RRF로 합친다(기본은 키워드 쪽 가중).
 * {@code search.pipeline.plan-retriever}(서비스) 또는 {@code search.plan-eval.plan-retriever}(요금제 평가)에 {@value #BEAN_NAME}을
 * 적어서 끼운다.
 *
 * <p>{@link PlanRetriever}의 약속을 지킨다: 후보 순서는 RRF 최종 순위, {@code similarity}는 코사인 유사도, 요금제 속성은 원본 값.
 * 요금제 검색은 이 후보 위에서 질문 조건(가격·데이터량 등) 매칭을 하므로, Hybrid의 순서는 조건이 없거나 매칭이 안 될 때(벡터 경로) 쓰인다.
 * 키워드로 쓸 텍스트가 비면 벡터 검색과 같은 결과를 돌려준다.
 */
@Component(HybridPlanRetriever.BEAN_NAME)
@ConditionalOnProperty(prefix = "search.hybrid", name = "enabled", havingValue = "true")
public class HybridPlanRetriever implements PlanRetriever {

	/** 설정에서 이 검색기를 가리킬 때 쓰는 빈 이름. */
	public static final String BEAN_NAME = "hybridPlanRetriever";

	private final PlanHybridSearchRepository hybridRepository;
	private final PlanVectorSearchRepository vectorRepository;
	private final HybridProperties.Side settings;

	public HybridPlanRetriever(PlanHybridSearchRepository hybridRepository, PlanVectorSearchRepository vectorRepository,
			HybridProperties properties) {
		this.hybridRepository = hybridRepository;
		this.vectorRepository = vectorRepository;
		this.settings = properties.plan();
	}

	@Override
	public List<PlanSimilarityResult> retrieve(RetrievalQuery query, int poolSize) {
		String keywordText = Bm25QueryText.from(query.text());
		if (keywordText.isEmpty()) {
			return vectorRepository.searchBySimilarity(query.vector(), 0.0, poolSize);
		}
		return hybridRepository.search(query.vector(), keywordText, settings.weights(), settings.candidateLimitFor(poolSize), poolSize);
	}
}
