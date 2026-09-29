package com.vita.search.service;

/**
 * 사용자 질문에서 뽑아낸 요금제 조건. 값이 null인 항목은 "질문에 그 조건이 없다"는 뜻이다.
 *
 * <p>범위 조건은 양쪽 끝을 포함(이상/이하)하는 구간이다. 정확히 일치하는 조건은 min = max로 표현한다.
 *
 * @param feeMin     월 요금 하한(원)
 * @param feeMax     월 요금 상한(원)
 * @param dataMbMin  기본 데이터 하한(MB)
 * @param dataMbMax  기본 데이터 상한(MB). 값이 있으면 무제한 요금제는 조건에서 빠진다.
 * @param targetGroup 대상 그룹(plans.target_group 값: GENERAL/YOUTH/SENIOR/KIDS/WATCH/TABLET)
 * @param dataPolicy 데이터 정책(plans.data_policy 값: LIMITED/UNLIMITED)
 */
public record PlanQueryConditions(
		Integer feeMin,
		Integer feeMax,
		Long dataMbMin,
		Long dataMbMax,
		String targetGroup,
		String dataPolicy) {

	/** 추출된 조건이 하나도 없는지. */
	public boolean isEmpty() {
		return feeMin == null && feeMax == null && dataMbMin == null && dataMbMax == null
				&& targetGroup == null && dataPolicy == null;
	}

	/**
	 * 데이터 정책 조건만 뺀 사본. "청년 요금제 무제한 있어?"처럼 조건을 모두 만족하는 요금제가 없을 때,
	 * 가장 덜 확실한 조건(무제한 여부)을 포기하고 다시 찾기 위해 쓴다.
	 */
	public PlanQueryConditions withoutDataPolicy() {
		return new PlanQueryConditions(feeMin, feeMax, dataMbMin, dataMbMax, targetGroup, null);
	}
}
