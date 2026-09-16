package com.vita.search.service;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.search.dto.FaqSearchResult;
import com.vita.search.entity.Faq;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqSearchRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/** FAQ 키워드 검색 API의 검증·정책(ACTIVE만 검색 등)을 담당하는 서비스. */
@Service
@RequiredArgsConstructor
public class FaqSearchService {

	/** 정렬 화이트리스트 — 존재하지 않는 컬럼으로 정렬 시도 시 500 대신 400을 내기 위함. */
	private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "createdAt", "category");
	private static final String DEFAULT_SORT = "id,asc";

	private final FaqSearchRepository faqSearchRepository;

	/**
	 * keyword 필수 검증 후, ACTIVE 상태 FAQ만 대상으로 키워드 검색을 수행한다.
	 *
	 * @throws BusinessException keyword가 비어있는 경우 (VALIDATION_ERROR)
	 */
	public PageResponse<FaqSearchResult> search(PageRequest pageRequest) {
		if (pageRequest.keyword() == null || pageRequest.keyword().isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "keyword는 필수입니다.");
		}

		Page<Faq> page = faqSearchRepository.searchByKeyword(
				pageRequest.keyword(),
				FaqStatus.ACTIVE,
				pageRequest.toSpringPageRequest(ALLOWED_SORT_FIELDS, DEFAULT_SORT));

		return PageResponse.from(page, FaqSearchResult::from);
	}
}
