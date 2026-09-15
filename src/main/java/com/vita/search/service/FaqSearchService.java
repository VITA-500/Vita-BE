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

@Service
@RequiredArgsConstructor
public class FaqSearchService {

	private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "createdAt", "category");
	private static final String DEFAULT_SORT = "id,asc";

	private final FaqSearchRepository faqSearchRepository;

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
