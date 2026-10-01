package com.vita.search.dto;

import java.time.LocalDateTime;

/**
 * pgvector 유사도 검색(PlanVectorSearchRepository) 결과 한 건.
 *
 * <p>description 뒤의 상세 필드는 {@link PlanReference}로 그대로 전달된다(각 필드의 의미는 그쪽 Javadoc 참고).
 *
 * @param similarity 코사인 유사도, 0~1 범위 (1에 가까울수록 유사)
 */
public record PlanSimilarityResult(
		Long id,
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
