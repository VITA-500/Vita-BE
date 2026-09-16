package com.vita.search.service;

import com.vita.search.dto.FaqRetrievalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 임시 구현체. 사용자 질문을 벡터로 바꾸는 BE2 인터페이스가 아직 없어서 항상
 * 빈 결과만 반환한다 — BE4가 {@link FaqRetrievalService}에 의존하는 코드를
 * 지금 바로 짜고 스프링 빈 주입까지 정상 동작하도록 하기 위한 자리표시자다.
 * BE2 작업이 끝나면 이 클래스 내부만 {@link com.vita.search.repository.FaqVectorSearchRepository}를
 * 쓰는 실제 로직으로 교체하면 되고, {@link FaqRetrievalService} 인터페이스는 그대로 유지된다.
 */
@Slf4j
@Service
public class FaqRetrievalServiceImpl implements FaqRetrievalService {

	/** 항상 빈 컨텍스트를 반환하는 임시 구현. 실제 로직은 BE2 인터페이스 확보 후 채운다. */
	@Override
	public FaqRetrievalContext search(String query, int topK) {
		log.warn("FaqRetrievalServiceImpl은 아직 임시 구현체라 항상 빈 결과를 반환합니다. query={}", query);
		return FaqRetrievalContext.empty();
	}
}
