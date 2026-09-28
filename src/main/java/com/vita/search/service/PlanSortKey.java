package com.vita.search.service;

/** 요금제 비교·최상급 질문에서 무엇을 기준으로 정렬할지. */
public enum PlanSortKey {
	/** 월정액이 가장 저렴한 순. */
	CHEAPEST,
	/** 월정액이 가장 비싼 순. */
	MOST_EXPENSIVE,
	/** 데이터 제공량이 가장 많은 순 (무제한 요금제를 최우선으로 취급). */
	MOST_DATA,
	/** 데이터 제공량이 가장 적은 순 (무제한 요금제는 "적은 데이터"가 아니므로 최하위로 취급). */
	LEAST_DATA
}
