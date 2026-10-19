package com.vita.trace;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** 관리자 조회 API가 돌려주는 응답 모양들. 질문 벡터는 빼고 개인정보는 가린 값만 담는다. */
public final class TraceViews {

	private TraceViews() {
	}

	/**
	 * 목록의 한 줄.
	 *
	 * @param question         사용자 질문 원문(개인정보 마스킹됨)
	 * @param unresolvedReason 미해결 질문으로 판정된 사유. 해결된 질문이면 null
	 * @param totalMs          첫 단계 시작부터 마지막 단계 종료까지의 시간(밀리초)
	 * @param hasError         오류가 기록된 단계가 있는지
	 */
	public record Item(Long id, Long messageId, LocalDateTime createdAt, String appVersion, String question,
			String unresolvedReason, Long totalMs, boolean hasError) {
	}

	/**
	 * 기록된 값만으로 계산한 자동 표시.
	 *
	 * @param code    SEARCH_FAILURE_SUSPECTED / SLOW / TRANSFORM_FAILED / RULE_BLOCKED / SKIPPED / ERROR
	 * @param message 화면에 보여줄 설명
	 * @param spanId  해당하는 단계 id. 전체에 해당하면 null
	 */
	public record Flag(String code, String message, Long spanId) {
	}

	/**
	 * 단계 하나.
	 *
	 * @param skipped 건너뛴 이유. 건너뛰지 않았으면 null
	 * @param error   오류 정보({type, message}). 오류가 없으면 null
	 * @param history 단계별 원문 스냅샷. 질문 벡터는 빠지고 개인정보는 마스킹돼 있다
	 */
	public record SpanView(Long id, String type, LocalDateTime startedAt, LocalDateTime endedAt, long tookMs,
			String skipped, JsonNode error, JsonNode history) {
	}

	/** 질문 1건의 처리 과정 전체. */
	public record Detail(Long id, Long messageId, LocalDateTime createdAt, String appVersion, JsonNode config,
			long totalMs, List<Flag> flags, List<SpanView> spans) {
	}
}
