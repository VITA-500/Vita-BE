package com.vita.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 보관 기간 기준 시각 계산, 나눠 지우기, 실패·비활성에서 예외를 던지지 않는지 확인한다. */
class TraceRetentionJobTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneId.of("UTC"));

	private final TraceRepository repository = mock(TraceRepository.class);

	@Test
	void deletesRecordsOlderThanTheRetentionDays() {
		when(repository.deleteCreatedBefore(any(), anyInt())).thenReturn(3);

		new TraceRetentionJob(repository, 90, CLOCK).purgeExpired();

		ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(repository).deleteCreatedBefore(cutoff.capture(), anyInt());
		assertThat(cutoff.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 19, 0, 0).minusDays(90));
	}

	@Test
	void keepsDeletingInBatchesUntilABatchIsNotFull() {
		when(repository.deleteCreatedBefore(any(), anyInt()))
				.thenReturn(TraceRetentionJob.BATCH_SIZE, TraceRetentionJob.BATCH_SIZE, 120);

		new TraceRetentionJob(repository, 90, CLOCK).purgeExpired();

		verify(repository, times(3)).deleteCreatedBefore(any(), anyInt());
	}

	@Test
	void stopsAtTheBatchLimitEvenIfRecordsKeepComing() {
		when(repository.deleteCreatedBefore(any(), anyInt())).thenReturn(TraceRetentionJob.BATCH_SIZE);

		new TraceRetentionJob(repository, 90, CLOCK).purgeExpired();

		verify(repository, times(TraceRetentionJob.MAX_BATCHES)).deleteCreatedBefore(any(), anyInt());
	}

	@Test
	void doesNothingWhenRetentionIsZeroOrNegative() {
		new TraceRetentionJob(repository, 0, CLOCK).purgeExpired();
		new TraceRetentionJob(repository, -1, CLOCK).purgeExpired();

		verify(repository, never()).deleteCreatedBefore(any(), anyInt());
	}

	@Test
	void doesNotThrowWhenDeletingFails() {
		when(repository.deleteCreatedBefore(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

		assertThatCode(() -> new TraceRetentionJob(repository, 90, CLOCK).purgeExpired()).doesNotThrowAnyException();
	}
}
