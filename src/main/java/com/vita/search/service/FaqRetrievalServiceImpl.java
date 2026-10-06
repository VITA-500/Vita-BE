package com.vita.search.service;

import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.pipeline.RetrievalPipeline;
import org.springframework.stereotype.Service;

/**
 * {@link FaqRetrievalService}의 실제 구현. 검색 흐름(질문 변환 → 임베딩 → 후보 검색 → 요금제 검색 → 후처리·threshold)은
 * {@link RetrievalPipeline}이 담당하고, 이 클래스는 BE4가 부르는 진입점으로서 파이프라인 결과 중 응답({@link FaqRetrievalContext})만
 * 돌려준다. 평가 러너가 같은 파이프라인을 직접 부르므로, 검색 로직을 바꿀 때는 파이프라인과 그 구성 요소를 고치면 서비스와 평가가 함께 바뀐다.
 */
@Service
public class FaqRetrievalServiceImpl implements FaqRetrievalService {

	private final RetrievalPipeline retrievalPipeline;

	public FaqRetrievalServiceImpl(RetrievalPipeline retrievalPipeline) {
		this.retrievalPipeline = retrievalPipeline;
	}

	@Override
	public FaqRetrievalContext search(String query, int topK) {
		return retrievalPipeline.run(query, topK).context();
	}
}
