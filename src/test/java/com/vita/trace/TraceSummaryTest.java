package com.vita.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 저장할 때 뽑는 목록용 요약(질문, 미해결 사유, 총 시간, 오류 여부)이 단계 기록과 맞게 계산되는지 확인한다. */
class TraceSummaryTest {

	private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 19, 14, 0, 0);

	private static SpanRecord span(SpanType type, int startSec, int endSec, Map<String, Object> history) {
		return new SpanRecord(type, T0.plusSeconds(startSec), T0.plusSeconds(endSec), history);
	}

	private static TraceSummary summary(SpanRecord... spans) {
		return TraceSummary.of(new TraceRecord(1L, null, Map.of(), T0, List.of(spans)));
	}

	@Test
	void takesTheQuestionFromTheTransformSpanAndTheReasonFromTheGenerationSpan() {
		TraceSummary summary = summary(
				span(SpanType.TRANSFORM, 0, 2, Map.of("question", "로밍 요금 알려줘")),
				span(SpanType.FAQ_RETRIEVE, 2, 3, Map.of()),
				span(SpanType.GENERATION, 3, 7, Map.of("unresolved_reason", "LOW_SIMILARITY")));

		assertThat(summary.question()).isEqualTo("로밍 요금 알려줘");
		assertThat(summary.unresolvedReason()).isEqualTo("LOW_SIMILARITY");
		assertThat(summary.totalMs()).isEqualTo(7000);
		assertThat(summary.hasError()).isFalse();
	}

	@Test
	void marksAnswerFailedWhenAnyStepExceptStoreLookupRecordedAnError() {
		TraceSummary summary = summary(
				span(SpanType.TRANSFORM, 0, 1, Map.of("question", "질문")),
				span(SpanType.GENERATION, 1, 2, Map.of("unresolved_reason", "LOW_SIMILARITY",
						"error", Map.of("type", "IllegalStateException", "message", "끊김"))));

		// 답변이 실패했으면 생성 단계의 판정 값보다 ANSWER_FAILED가 우선한다(chat_messages도 같은 기준이다).
		assertThat(summary.unresolvedReason()).isEqualTo("ANSWER_FAILED");
		assertThat(summary.hasError()).isTrue();
	}

	@Test
	void countsAFailedStoreLookupAsAnErrorButNotAsAFailedAnswer() {
		Map<String, Object> storeFailed = new HashMap<>();
		storeFailed.put("error", "IllegalStateException: DB 오류");
		TraceSummary summary = summary(
				span(SpanType.TRANSFORM, 0, 1, Map.of("question", "근처 매장")),
				span(SpanType.STORE_LOOKUP, 1, 2, storeFailed),
				span(SpanType.GENERATION, 2, 3, Map.of()));

		assertThat(summary.hasError()).isTrue();
		assertThat(summary.unresolvedReason()).isNull();
	}

	@Test
	void ignoresAnErrorKeyWhoseValueIsNullBecauseStoreLookupAlwaysWritesIt() {
		Map<String, Object> storeOk = new HashMap<>();
		storeOk.put("found", 3);
		storeOk.put("error", null);
		TraceSummary summary = summary(
				span(SpanType.TRANSFORM, 0, 1, Map.of("question", "근처 매장")),
				span(SpanType.STORE_LOOKUP, 1, 2, storeOk),
				span(SpanType.GENERATION, 2, 3, Map.of()));

		assertThat(summary.hasError()).isFalse();
	}

	@Test
	void cutsAnExtremelyLongQuestionAndHandlesATraceWithoutSpans() {
		String longQuestion = "가".repeat(TraceSummary.MAX_QUESTION_CHARS + 500);

		assertThat(summary(span(SpanType.TRANSFORM, 0, 1, Map.of("question", longQuestion))).question())
				.hasSize(TraceSummary.MAX_QUESTION_CHARS);
		TraceSummary empty = summary();
		assertThat(empty.question()).isNull();
		assertThat(empty.totalMs()).isNull();
		assertThat(empty.hasError()).isFalse();
	}
}
