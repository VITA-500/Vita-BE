package com.vita.common.page;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import java.util.Set;
import org.springframework.data.domain.Sort;

/**
 * 관리자 CRUD 목록 API 공통 요청 파라미터 — 04_API명세서 0절 "공통 페이징 요청/응답" 기준.
 * sortBy는 Spring Data 컨벤션 `{필드명},{direction}` 형식(예: createdAt,desc)이며, API마다 정해진
 * 화이트리스트 밖의 필드는 거부한다 — 아무 값이나 그대로 쿼리에 흘려보내면 존재하지 않는 컬럼을
 * 넣었을 때 500이 터지기 때문 (04_API명세서 8절 "정렬 가능 필드" 참고).
 */
public record PageRequest(int page, int size, String keyword, String sortBy) {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 20;

	public static PageRequest of(Integer page, Integer size, String keyword, String sortBy) {
		return new PageRequest(
				page != null ? page : DEFAULT_PAGE,
				size != null ? size : DEFAULT_SIZE,
				keyword,
				sortBy);
	}

	/**
	 * @param allowedSortFields 이 API에서 정렬을 허용하는 필드 화이트리스트
	 * @param defaultSort       sortBy 미지정 시 사용할 기본값, 예: "createdAt,desc"
	 */
	public org.springframework.data.domain.PageRequest toSpringPageRequest(Set<String> allowedSortFields, String defaultSort) {
		return org.springframework.data.domain.PageRequest.of(page, size, parseSort(allowedSortFields, defaultSort));
	}

	private Sort parseSort(Set<String> allowedSortFields, String defaultSort) {
		String raw = (sortBy != null && !sortBy.isBlank()) ? sortBy : defaultSort;
		String[] parts = raw.split(",", 2);
		String field = parts[0].trim();

		if (!allowedSortFields.contains(field)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "정렬 기준으로 사용할 수 없는 필드입니다: " + field);
		}

		Sort.Direction direction = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()))
				? Sort.Direction.ASC
				: Sort.Direction.DESC;

		return Sort.by(direction, field);
	}
}
