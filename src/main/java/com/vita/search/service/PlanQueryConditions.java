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
	 * 이 조건에서 비어 있는 항목을 다른 조건으로 채운 새 조건. 같은 항목이 양쪽에 있으면 이 조건의 값을 쓴다.
	 * 사용자 원문에서 읽은 조건을 우선하고, 원문에는 없지만 질문 변환 결과(요금제용 질문)에서 읽은 조건으로 보충할 때 쓴다.
	 *
	 * <p>월 요금 범위(feeMin·feeMax)와 데이터량 범위(dataMbMin·dataMbMax)는 각각 한 묶음으로 본다. 한쪽 끝만 섞으면
	 * "5만원 이하"와 "3만원 이상"이 합쳐져 사용자가 하지 않은 범위가 되기 때문이다.
	 *
	 * @param other 이 조건에 없는 항목을 채울 조건
	 */
	public PlanQueryConditions orElse(PlanQueryConditions other) {
		boolean ownFee = feeMin != null || feeMax != null;
		boolean ownData = dataMbMin != null || dataMbMax != null;
		return new PlanQueryConditions(
				ownFee ? feeMin : other.feeMin,
				ownFee ? feeMax : other.feeMax,
				ownData ? dataMbMin : other.dataMbMin,
				ownData ? dataMbMax : other.dataMbMax,
				targetGroup != null ? targetGroup : other.targetGroup,
				dataPolicy != null ? dataPolicy : other.dataPolicy);
	}

	/**
	 * 데이터 정책 조건만 뺀 사본. "청년 요금제 무제한 있어?"처럼 조건을 모두 만족하는 요금제가 없을 때,
	 * 가장 덜 확실한 조건(무제한 여부)을 포기하고 다시 찾기 위해 쓴다.
	 */
	public PlanQueryConditions withoutDataPolicy() {
		return new PlanQueryConditions(feeMin, feeMax, dataMbMin, dataMbMax, targetGroup, null);
	}
}
