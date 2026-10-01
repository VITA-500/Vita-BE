package com.vita.search.dto;

import java.time.LocalDateTime;

/**
 * RAG 응답에 참고 자료로 포함되는 요금제 한 건 — BE4/FE1에 전달되는 대외 계약.
 *
 * <p>description 뒤의 상세 필드는 FE 요금제 카드가 값을 칸마다 따로 표시할 수 있도록 plans 테이블의
 * 컬럼을 그대로 전달한다. 무제한·해당 없음은 null이 의미 있는 값이라 래퍼 타입(Integer/Long)을 쓴다.
 *
 * @param networkType        LTE / 5G / LTE_5G
 * @param targetGroup        GENERAL / YOUTH / SENIOR / KIDS / TABLET / WATCH
 * @param minAge             가입 가능 최소 나이. 제한 없으면 null
 * @param maxAge             가입 가능 최대 나이. 제한 없으면 null
 * @param dataPolicy         LIMITED / UNLIMITED
 * @param baseDataMb         기본 데이터량(MB). 무제한이면 null
 * @param exhaustedSpeedKbps 데이터 소진 후 속도(kbps). 소진 후 차단이거나 무제한이면 null
 * @param voicePolicy        NONE / LIMITED / UNLIMITED
 * @param voiceMinutes       기본 통화 분. 제한형이 아니면 null
 * @param smsPolicy          NONE / LIMITED / UNLIMITED
 * @param smsCount           기본 문자 건수. 제한형이 아니면 null
 */
public record PlanReference(
		Long planId,
		String planCode,
		String name,
		String summary,
		int monthlyFee,
		String description,
		double similarity,
		LocalDateTime updatedAt,
		String networkType,
		String targetGroup,
		Integer minAge,
		Integer maxAge,
		String dataPolicy,
		Long baseDataMb,
		Integer exhaustedSpeedKbps,
		String voicePolicy,
		Integer voiceMinutes,
		String smsPolicy,
		Integer smsCount) {
}
