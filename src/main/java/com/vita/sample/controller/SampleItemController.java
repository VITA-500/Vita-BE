package com.vita.sample.controller;

import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.sample.dto.SampleItemCreateRequest;
import com.vita.sample.dto.SampleItemDeletedResponse;
import com.vita.sample.dto.SampleItemResponse;
import com.vita.sample.service.SampleItemService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 패키지 구조/계층 패턴을 보여주기 위한 샘플 CRUD 엔드포인트.
 * 실제 도메인(auth/faq/chat/store 등)을 이 패턴 그대로 만들면 된다 — 이 컨트롤러 자체는
 * 실제 기능 명세(docs/03~04)에 없는 예시용이니 그대로 배포에 쓰지 말 것.
 *
 * 04_API명세서 0절 확정 컨벤션: 응답 wrapper 없이 DTO를 그대로 반환, 성공/실패는 HTTP 상태코드로 구분.
 */
@RestController
@RequestMapping("/samples")
public class SampleItemController {

	private final SampleItemService sampleItemService;

	public SampleItemController(SampleItemService sampleItemService) {
		this.sampleItemService = sampleItemService;
	}

	@GetMapping
	public PageResponse<SampleItemResponse> list(
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String sortBy) {

		return sampleItemService.list(PageRequest.of(page, size, keyword, sortBy));
	}

	@GetMapping("/{id}")
	public SampleItemResponse get(@PathVariable Long id) {
		return sampleItemService.get(id);
	}

	@PostMapping
	public ResponseEntity<SampleItemResponse> create(@Valid @RequestBody SampleItemCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(sampleItemService.create(request));
	}

	@PutMapping("/{id}")
	public SampleItemResponse update(@PathVariable Long id, @Valid @RequestBody SampleItemCreateRequest request) {
		return sampleItemService.update(id, request);
	}

	@DeleteMapping("/{id}")
	public SampleItemDeletedResponse delete(@PathVariable Long id) {
		return sampleItemService.delete(id);
	}
}
