package com.vita.search.service;

import com.vita.search.dto.PlanReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
	 * <p>"가장 저렴한 인터넷 요금제"처럼 모바일이 아닌 서비스(인터넷·IPTV·TV)의 요금을 묻는 질문이면 모바일 요금제를
	 * 돌려주지 않고 빈 목록을 돌려준다({@link NonMobilePlanQueryDetector}).
	 *
	 * @param query 사용자 질문 원문
	 */
	default List<PlanReference> findExtremeForQuery(PlanSortKey sortKey, int limit, String query) {
		if (limit < 1) {
			throw new IllegalArgumentException("limit은 1 이상이어야 합니다.");
		}
		if (NonMobilePlanQueryDetector.matches(query)) {
			return List.of();
		}
		String group = PlanQueryConditionExtractor.targetGroupOf(query);
		String scope = group != null ? group : GENERAL_GROUP;

		// "3만원대 중에서 가장 저렴한", "무제한 중에 제일 싼"처럼 질문에 가격·데이터량·무제한 조건이 함께 있으면 그 조건을 만족하는
		// 요금제 안에서 가장 ~한 것을 찾는다. 조건을 보지 않으면 조건 밖의 요금제(25,000원)가 "3만원대에서 가장 저렴한 것"으로 나간다.
		PlanQueryConditions narrowing = PlanQueryConditionExtractor.extractAssumingPlanContext(query).withoutTargetGroup();
		Set<String> allowed = narrowing.isEmpty() ? null : matchingPlanCodes(narrowing);

		// 조건으로 거를 때는 넉넉히 가져오고, 아니면 동률(같은 값이 여러 요금제)을 알아볼 만큼만 더 가져온다.
		int fetch = allowed != null ? EXTREME_FETCH_LIMIT : limit + MAX_TIED_EXTRA;
		List<PlanReference> sorted = findExtreme(sortKey, Math.max(fetch, limit), scope);
		if (allowed != null) {
			sorted = sorted.stream().filter(plan -> allowed.contains(plan.planCode())).toList();
		}
		return limitWithTies(sorted, sortKey, limit);
	}

	/** 조건으로 거를 때 한 번에 가져올 후보 수. 요금제가 20종 안팎이라 부담이 없다. */
	int EXTREME_FETCH_LIMIT = 50;

	/** 동률일 때 limit 개수에 더해 돌려줄 최대 개수. 같은 값이 많아도 목록이 길어지지 않게 한다. */
	int MAX_TIED_EXTRA = 2;

	/**
	 * 조건({@link PlanQueryConditions})을 모두 만족하는 요금제의 plan_code. 조건으로 거르는 것을 지원하지 않는 구현은 null을
	 * 돌려주며, 이때 극값 조회는 조건 없이 동작한다(기존 동작).
	 */
	default Set<String> matchingPlanCodes(PlanQueryConditions conditions) {
		return null;
	}

	/**
	 * 정렬된(가장 ~한 요금제가 앞) 목록에서 limit개를 고르되, 마지막으로 고른 요금제와 정렬 기준 값이 같은 요금제가 뒤에 이어지면
	 * (최대 {@link #MAX_TIED_EXTRA}개까지) 함께 돌려준다. "데이터 제일 많이 주는 요금제"에서 무제한 요금제가 둘이면 하나만 답하지
	 * 않고 둘 다 알려주기 위해서다.
	 *
	 * @param sortedBestFirst 정렬 기준에 따라 정렬된 목록
	 * @param sortKey         정렬 기준(동률 판단에 쓸 값을 정한다: 요금 또는 데이터량)
	 * @param limit           기본으로 고를 개수
	 */
	static List<PlanReference> limitWithTies(List<PlanReference> sortedBestFirst, PlanSortKey sortKey, int limit) {
		if (limit < 1) {
			return List.of();
		}
		if (sortedBestFirst.size() <= limit) {
			return List.copyOf(sortedBestFirst);
		}
		List<PlanReference> chosen = new ArrayList<>(sortedBestFirst.subList(0, limit));
		long boundary = sortValue(sortKey, chosen.get(limit - 1));
		for (int i = limit, extra = 0; i < sortedBestFirst.size() && extra < MAX_TIED_EXTRA; i++, extra++) {
			if (sortValue(sortKey, sortedBestFirst.get(i)) != boundary) {
				break;
			}
			chosen.add(sortedBestFirst.get(i));
		}
		return List.copyOf(chosen);
	}

	/** 동률 판단에 쓰는 값. 요금 기준이면 월 요금, 데이터 기준이면 데이터량(무제한은 가장 큰 값, 정보가 없으면 -1). */
	private static long sortValue(PlanSortKey sortKey, PlanReference plan) {
		return switch (sortKey) {
			case CHEAPEST, MOST_EXPENSIVE -> plan.monthlyFee();
			case MOST_DATA, LEAST_DATA -> "UNLIMITED".equals(plan.dataPolicy())
					? Long.MAX_VALUE
					: plan.baseDataMb() == null ? -1L : plan.baseDataMb();
		};
	}

	/**
	 * 조회 범위를 지정하는 버전. 질문에 "청년", "시니어", "워치"처럼 대상이 나오면 BE4가 그 그룹을 넘겨서
	 * "청년 요금제 중 가장 저렴한 것"을 정확히 답할 수 있다.
	 *
	 * @param targetGroup 대상 그룹(GENERAL/TEEN/YOUTH/SENIOR/KIDS/WATCH/TABLET). null이면 전체 요금제.
	 */
	List<PlanReference> findExtreme(PlanSortKey sortKey, int limit, String targetGroup);
}
