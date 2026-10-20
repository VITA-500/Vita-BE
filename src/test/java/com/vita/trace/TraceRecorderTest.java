package com.vita.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 저장이 답변 흐름과 분리되고, 실패·포화·비활성에서도 예외를 밖으로 던지지 않는지 확인한다. */
class TraceRecorderTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneId.of("UTC"));
	private static final Executor DIRECT = Runnable::run;

	private final TraceRepository repository = mock(TraceRepository.class);

	private TraceCollector collectorWithOneSpan(TraceRecorder recorder, Long messageId) {
		TraceCollector collector = recorder.start(messageId);
		collector.begin(SpanType.TRANSFORM).end(Map.of("question", "원문"));
		return collector;
	}

	@Test
	void savesTheSnapshotThroughTheExecutor() {
		TraceRecorder recorder = new TraceRecorder(repository, DIRECT, true, "abc1234", CLOCK);

		recorder.saveAsync(collectorWithOneSpan(recorder, 11L));

		ArgumentCaptor<TraceRecord> captor = ArgumentCaptor.forClass(TraceRecord.class);
		verify(repository).save(captor.capture());
		assertThat(captor.getValue().messageId()).isEqualTo(11L);
		assertThat(captor.getValue().appVersion()).isEqualTo("abc1234");
		assertThat(captor.getValue().spans()).extracting(SpanRecord::type).containsExactly(SpanType.TRANSFORM);
	}

	@Test
	void doesNotThrowWhenTheRepositoryFails() {
		doThrow(new IllegalStateException("db down")).when(repository).save(any());
		TraceRecorder recorder = new TraceRecorder(repository, DIRECT, true, null, CLOCK);

		assertThatCode(() -> recorder.saveAsync(collectorWithOneSpan(recorder, 11L))).doesNotThrowAnyException();
	}

	@Test
	void doesNotThrowWhenTheExecutorRejectsTheTask() {
		Executor full = task -> {
			throw new RejectedExecutionException("queue full");
		};
		TraceRecorder recorder = new TraceRecorder(repository, full, true, null, CLOCK);

		assertThatCode(() -> recorder.saveAsync(collectorWithOneSpan(recorder, 11L))).doesNotThrowAnyException();
		verify(repository, never()).save(any());
	}

	@Test
	void savesNothingWhenDisabled() {
		TraceRecorder recorder = new TraceRecorder(repository, DIRECT, false, null, CLOCK);

		recorder.saveAsync(collectorWithOneSpan(recorder, 11L));

		assertThat(recorder.enabled()).isFalse();
		verify(repository, never()).save(any());
	}

	@Test
	void savesNothingWithoutAMessageIdOrACollector() {
		TraceRecorder recorder = new TraceRecorder(repository, DIRECT, true, null, CLOCK);

		recorder.saveAsync(collectorWithOneSpan(recorder, null));
		recorder.saveAsync(null);

		verify(repository, never()).save(any());
	}

	@Test
	void treatsABlankAppVersionAsUnknown() {
		TraceRecorder recorder = new TraceRecorder(repository, DIRECT, true, "  ", CLOCK);

		recorder.saveAsync(collectorWithOneSpan(recorder, 11L));

		ArgumentCaptor<TraceRecord> captor = ArgumentCaptor.forClass(TraceRecord.class);
		verify(repository).save(captor.capture());
		assertThat(captor.getValue().appVersion()).isNull();
	}

	@Test
	void takesTheSnapshotOnTheCallingThreadSoLaterChangesAreNotSaved() {
		List<Runnable> queued = new ArrayList<>();
		TraceRecorder recorder = new TraceRecorder(repository, queued::add, true, null, CLOCK);
		TraceCollector collector = collectorWithOneSpan(recorder, 11L);

		recorder.saveAsync(collector);
		collector.begin(SpanType.GENERATION).end(Map.of("late", true));
		queued.forEach(Runnable::run);

		ArgumentCaptor<TraceRecord> captor = ArgumentCaptor.forClass(TraceRecord.class);
		verify(repository).save(captor.capture());
		assertThat(captor.getValue().spans()).hasSize(1);
	}
}
