package com.vita.sample.service;

import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.sample.SampleItemNotFoundException;
import com.vita.sample.dto.SampleItemCreateRequest;
import com.vita.sample.dto.SampleItemDeletedResponse;
import com.vita.sample.dto.SampleItemResponse;
import com.vita.sample.entity.SampleItem;
import com.vita.sample.repository.SampleItemRepository;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계층: Controller(얇게) → Service(@Transactional) → Repository, 생성자 주입만 사용,
 * 읽기 전용 쿼리는 @Transactional(readOnly = true) (08_개발표준 2절).
 */
@Service
@Transactional(readOnly = true)
public class SampleItemService {

	// 04_API명세서 8절 "정렬 가능 필드" 패턴 — 이 API가 허용하는 sortBy 화이트리스트.
	private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "title");
	private static final String DEFAULT_SORT = "createdAt,desc";

	private final SampleItemRepository sampleItemRepository;

	public SampleItemService(SampleItemRepository sampleItemRepository) {
		this.sampleItemRepository = sampleItemRepository;
	}

	public PageResponse<SampleItemResponse> list(PageRequest pageRequest) {
		var springPageRequest = pageRequest.toSpringPageRequest(ALLOWED_SORT_FIELDS, DEFAULT_SORT);
		var page = (pageRequest.keyword() != null && !pageRequest.keyword().isBlank())
				? sampleItemRepository.findByTitleContainingIgnoreCase(pageRequest.keyword(), springPageRequest)
				: sampleItemRepository.findAll(springPageRequest);

		return PageResponse.from(page, SampleItemResponse::from);
	}

	public SampleItemResponse get(Long id) {
		return SampleItemResponse.from(getOrThrow(id));
	}

	@Transactional
	public SampleItemResponse create(SampleItemCreateRequest request) {
		SampleItem item = new SampleItem(request.title(), request.content());
		return SampleItemResponse.from(sampleItemRepository.save(item));
	}

	@Transactional
	public SampleItemResponse update(Long id, SampleItemCreateRequest request) {
		SampleItem item = getOrThrow(id);
		item.update(request.title(), request.content());
		return SampleItemResponse.from(item);
	}

	@Transactional
	public SampleItemDeletedResponse delete(Long id) {
		sampleItemRepository.delete(getOrThrow(id));
		return new SampleItemDeletedResponse(id, true);
	}

	private SampleItem getOrThrow(Long id) {
		return sampleItemRepository.findById(id).orElseThrow(SampleItemNotFoundException::new);
	}
}
