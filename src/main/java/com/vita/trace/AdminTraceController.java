package com.vita.trace;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.trace.TraceQueryRepository.Filter;

import lombok.RequiredArgsConstructor;

/**
 * 질문 처리 기록(trace/span) 조회. 질문 원문과 프롬프트가 들어 있어 관리자만 접근한다(/admin/** 는 ADMIN 권한).
 * 응답에서는 개인정보를 가리고 질문 벡터를 뺀다.
 */
@RestController
@RequestMapping("/admin/traces")
@RequiredArgsConstructor
public class AdminTraceController {

	private final TraceQueryService traceQueryService;

	/**
	 * 목록. 최근 순이 기본이다.
	 *
	 * @param messageId        특정 답변의 기록만(답변 피드백 화면에서 넘어올 때). 같은 답변에 기록이 여러 개면 id가 큰 쪽이 최신이다
	 * @param from             이 시각 이후(포함). 예: 2026-10-19T00:00:00
	 * @param to               이 시각 이전(미포함)
	 * @param unresolvedReason NO_MATCH / LOW_SIMILARITY / ANSWER_FAILED 등 미해결 사유
	 * @param keyword          질문에 포함된 글자
	 * @param sortBy           createdAt, totalMs, id 중 하나와 방향(예: totalMs,desc)
	 */
	@GetMapping
	public PageResponse<TraceViews.Item> list(
			@RequestParam(required = false) Long messageId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
			@RequestParam(required = false) String unresolvedReason,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@RequestParam(required = false) String sortBy) {
		return traceQueryService.list(new Filter(messageId, from, to, unresolvedReason, keyword),
				PageRequest.of(page, size, keyword, sortBy));
	}

	/** 상세. 단계는 일어난 순서이고, flags에 자동 표시(검색 실패 의심, 느림 등)가 담긴다. */
	@GetMapping("/{id}")
	public TraceViews.Detail detail(@PathVariable Long id) {
		return traceQueryService.detail(id);
	}
}
