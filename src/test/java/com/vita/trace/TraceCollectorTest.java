package com.vita.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 단계별 기록이 순서·시각·오류와 함께 모이는지, 끝나지 않은 단계가 사라지지 않는지 확인한다. */
class TraceCollectorTest {

	/** 시각을 부를 때마다 1초씩 흐르는 시계. */
	private static final class TickingClock extends Clock {
		private Instant now = Instant.parse("2026-10-19T00:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			Instant current = now;
			now = now.plusSeconds(1);
			return current;
		}
	}

	private static TraceCollector collector() {
		return new TraceCollector(7L, new TickingClock());
	}

	@Test
	void keepsSpansInTheOrderTheyStartedWithTheirTimesAndHistory() {
		TraceCollector collector = collector();
		collector.begin(SpanType.TRANSFORM).end(Map.of("question", "원문 질문"));
		collector.begin(SpanType.GENERATION).end(Map.of("answer", "답변"));

		TraceRecord record = collector.snapshot("abc1234");

		assertThat(record.messageId()).isEqualTo(7L);
		assertThat(record.appVersion()).isEqualTo("abc1234");
		assertThat(record.spans()).extracting(SpanRecord::type).containsExactly(SpanType.TRANSFORM, SpanType.GENERATION);
		assertThat(record.spans().get(0).history()).containsEntry("question", "원문 질문");
		assertThat(record.spans().get(1).history()).containsEntry("answer", "답변");
		assertThat(record.spans()).allSatisfy(span -> assertThat(span.endedAt()).isAfter(span.startedAt()));
		assertThat(record.spans().get(1).startedAt()).isAfter(record.spans().get(0).startedAt());
	}

	@Test
	void failAddsTheErrorTypeAndMessageToWhatWasCollectedSoFar() {
		TraceCollector collector = collector();
		Map<String, Object> partial = new LinkedHashMap<>();
		partial.put("query", "FAQ 검색 질의");

		collector.begin(SpanType.FAQ_RETRIEVE).fail(new IllegalStateException("임베딩 서버 응답 없음"), partial);

		Map<String, Object> history = collector.snapshot(null).spans().get(0).history();
		assertThat(history).containsEntry("query", "FAQ 검색 질의");
		assertThat(history.get("error")).isEqualTo(Map.of("type", "IllegalStateException", "message", "임베딩 서버 응답 없음"));
	}

	@Test
	void closesASpanThatWasNeverEndedAsInterruptedSoItIsNotLost() {
		TraceCollector collector = collector();
		collector.begin(SpanType.TRANSFORM).end(Map.of("ok", true));
		collector.begin(SpanType.GENERATION);

		TraceRecord record = collector.snapshot(null);

		assertThat(record.spans()).hasSize(2);
		SpanRecord interrupted = record.spans().get(1);
		assertThat(interrupted.endedAt()).isAfterOrEqualTo(interrupted.startedAt());
		assertThat(interrupted.history().get("error")).isInstanceOf(Map.class);
		assertThat(record.spans().get(0).history()).doesNotContainKey("error");
	}

	@Test
	void ignoresASecondEndOrFailOnTheSameSpan() {
		TraceCollector collector = collector();
		TraceCollector.Span span = collector.begin(SpanType.TRANSFORM);
		span.end(Map.of("first", 1));
		span.end(Map.of("second", 2));
		span.fail(new RuntimeException("늦은 오류"), Map.of("third", 3));

		Map<String, Object> history = collector.snapshot(null).spans().get(0).history();
		assertThat(history).containsOnlyKeys("first");
	}

	@Test
	void collectsConfigAndCopiesItIntoTheSnapshot() {
		TraceCollector collector = collector();
		collector.putConfig("faq_threshold", 0.83);
		collector.putConfig("faq_threshold", 0.84);

		TraceRecord record = collector.snapshot(null);
		collector.putConfig("late", true);

		assertThat(record.config()).containsEntry("faq_threshold", 0.84).doesNotContainKey("late");
	}

	@Test
	void acceptsNullHistoryAndNullCause() {
		TraceCollector collector = collector();
		collector.begin(SpanType.TRANSFORM).end(null);
		collector.begin(SpanType.GENERATION).fail(null, null);

		TraceRecord record = collector.snapshot(null);

		assertThat(record.spans().get(0).history()).isEmpty();
		assertThat(record.spans().get(1).history().get("error")).isEqualTo(Map.of("type", "Unknown", "message", ""));
	}
}
