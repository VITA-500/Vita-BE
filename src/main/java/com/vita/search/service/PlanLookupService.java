package com.vita.search.service;

import com.vita.search.dto.PlanReference;
import java.util.List;

/**
 * BE4가 요금제 비교·최상급 질문(가장 저렴한/비싼, 데이터 제일 많은/적은)의 의도를 감지했을 때
 * 호출하는 정형 조회 진입점. {@link FaqRetrievalService}(유사도 검색)와는 별도 경로다 —
 * 이런 질문은 벡터 유사도로 풀 수 없고(실측으로 확인, monthly_fee/base_data_mb 실제 값
 * 비교 불가) 정확한 조건 매칭이 필요하기 때문이다.
 */
public interface PlanLookupService {

	/**
	 * @param sortKey 조회 기준
	 * @param limit   최대 반환 개수 (권장 1~3)
	 * @return sortKey 기준으로 정렬된 ACTIVE 요금제 목록.
	 */
	List<PlanReference> findExtreme(PlanSortKey sortKey, int limit);
}
