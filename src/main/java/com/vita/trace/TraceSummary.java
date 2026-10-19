package com.vita.trace;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 목록 조회에 쓰는 trace 요약. 저장할 때 단계 기록에서 한 번 뽑아 {@code traces}의 컬럼에 함께 넣는다. 목록·정렬·검색을 할 때마다
 * 수만 건의 단계 history(수 KB~수십 KB의 jsonb)를 풀어 보지 않으려는 것이다.
 *
 * @param question         사용자 질문 원문. transform 단계에 기록된 값이고, 없으면 null
 * @param unresolvedReason 미해결 사유. 답변이 실패했으면 {@code ANSWER_FAILED}, 아니면 답변 생성 단계에 남은 판정 값
 * @param totalMs          첫 단계 시작부터 마지막 단계 종료까지(밀리초). 단계가 없으면 null
 * @param hasError         오류가 기록된 단계가 있는지(매장 조회가 실패해도 답변은 계속되지만 오류로 센다)
 */
record TraceSummary(String question, String unresolvedReason, Integer totalMs, boolean hasError) {

	/** 저장하는 질문의 최대 글자 수. 아주 긴 입력이 목록 테이블을 키우지 않게 한다. */
	static final int MAX_QUESTION_CHARS = 2000;

	static final String ANSWER_FAILED = "ANSWER_FAILED";

	static TraceSummary of(TraceRecord trace) {
		String question = null;
		String generationReason = null;
		boolean hasError = false;
		boolean answerFailed = false;
		LocalDateTime first = null;
		LocalDateTime last = null;

		for (SpanRecord span : trace.spans()) {
			Object history = span.history() == null ? null : span.history().get("error");
			// 값이 null인 error 키는 오류가 아니다(매장 조회 기록은 오류가 없어도 error 키를 null로 둔다).
			if (history != null) {
				hasError = true;
				// 매장 조회 실패는 매장 정보 없이 답변이 계속되므로, 답변 실패로 세지 않는다.
				if (span.type() != SpanType.STORE_LOOKUP) {
					answerFailed = true;
				}
			}
			if (question == null && span.type() == SpanType.TRANSFORM && span.history() != null
					&& span.history().get("question") instanceof String text) {
				question = text.length() > MAX_QUESTION_CHARS ? text.substring(0, MAX_QUESTION_CHARS) : text;
			}
			if (span.type() == SpanType.GENERATION && span.history() != null
					&& span.history().get("unresolved_reason") instanceof String reason) {
				generationReason = reason;
			}
			if (first == null || span.startedAt().isBefore(first)) {
				first = span.startedAt();
			}
			if (last == null || span.endedAt().isAfter(last)) {
				last = span.endedAt();
			}
		}

		Integer totalMs = null;
		if (first != null) {
			totalMs = (int) Math.min(Integer.MAX_VALUE, Duration.between(first, last).toMillis());
		}
		return new TraceSummary(question, answerFailed ? ANSWER_FAILED : generationReason, totalMs, hasError);
	}
}
