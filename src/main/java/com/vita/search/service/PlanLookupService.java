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

	/** 범용 요금제(연령·기기 제한이 없는 요금제)를 뜻하는 대상 그룹 값(plans.target_group). */
	String GENERAL_GROUP = "GENERAL";

	/**
	 * 범용(GENERAL) 요금제 중에서 sortKey 기준으로 조회한다. 사용자가 기기나 연령을 말하지 않은
	 * "가장 저렴한 요금제"에 워치·태블릿 전용(11,000원)이나 연령 제한 요금제가 답으로 나오는 것을 막기 위해
	 * 기본 범위를 범용 요금제로 좁혔다. 이렇게 좁힌 결과라는 사실을 답변에 안내하는 것은 BE4 프롬프트의 몫이다.
	 *
	 * @param sortKey 조회 기준
	 * @param limit   최대 반환 개수 (권장 1~3)
	 * @return sortKey 기준으로 정렬된 범용 ACTIVE 요금제 목록.
	 */
	default List<PlanReference> findExtreme(PlanSortKey sortKey, int limit) {
		return findExtreme(sortKey, limit, GENERAL_GROUP);
	}

	/**
	 * 질문에서 대상 그룹을 읽어 조회 범위를 정하는 버전. "워치 요금제 중 제일 싼 거", "청년 요금제 중 가장
	 * 저렴한 것"처럼 질문에 대상이 있으면 그 그룹 안에서, 없으면 범용(GENERAL) 요금제 안에서 조회한다.
	 * 호출하는 쪽(BE4)이 그룹을 직접 판별하지 않고 원래 질문만 넘기면 된다.
	 *
	 * <p>"청년이랑 시니어 중 가장 싼 것"처럼 여러 그룹이 섞이면 어느 한 그룹으로 좁힐 수 없어 범용으로 본다.
	 *
	 * @param query 사용자 질문 원문
	 */
	default List<PlanReference> findExtremeForQuery(PlanSortKey sortKey, int limit, String query) {
		String group = PlanQueryConditionExtractor.targetGroupOf(query);
		return findExtreme(sortKey, limit, group != null ? group : GENERAL_GROUP);
	}

	/**
	 * 조회 범위를 지정하는 버전. 질문에 "청년", "시니어", "워치"처럼 대상이 나오면 BE4가 그 그룹을 넘겨서
	 * "청년 요금제 중 가장 저렴한 것"을 정확히 답할 수 있다.
	 *
	 * @param targetGroup 대상 그룹(GENERAL/YOUTH/SENIOR/KIDS/WATCH/TABLET). null이면 전체 요금제.
	 */
	List<PlanReference> findExtreme(PlanSortKey sortKey, int limit, String targetGroup);
}
