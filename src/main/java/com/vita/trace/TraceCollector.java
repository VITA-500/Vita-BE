package com.vita.trace;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 질문 1건을 처리하는 동안 단계(Span)별 기록을 메모리에 모으는 도구. 답변 흐름이 끝난 뒤 {@link TraceRecorder}가 한 번에 저장한다.
 *
 * <p>사용 순서는 {@code begin(type)}으로 단계를 시작하고, 끝나면 {@link Span#end}(정상) 또는 {@link Span#fail}(오류)을 부른다.
 * 오류로 흐름이 중간에 끊겨 끝내지 못한 단계는 {@link #snapshot}에서 "중단됨"으로 닫아 기록이 사라지지 않게 한다.
 * 단계 기록은 답변 생성 스레드 한 곳에서 이어서 쓰이지만, 저장을 맡기는 스레드가 달라도 안전하도록 메서드를 동기화했다.
 */
public final class TraceCollector {

	private final Long messageId;
	private final Clock clock;
	private final LocalDateTime startedAt;
	private final Map<String, Object> config = new LinkedHashMap<>();
	private final List<Span> spans = new ArrayList<>();

	TraceCollector(Long messageId, Clock clock) {
		this.messageId = messageId;
		this.clock = clock;
		this.startedAt = LocalDateTime.now(clock);
	}

	/** 단계를 시작한다. 시작 시각을 지금으로 기록하고, 끝낼 때 쓸 {@link Span}을 돌려준다. */
	public synchronized Span begin(SpanType type) {
		Span span = new Span(type, LocalDateTime.now(clock));
		spans.add(span);
		return span;
	}

	/** 설정 스냅샷에 값을 하나 더한다. 같은 키를 다시 넣으면 덮어쓴다. */
	public synchronized void putConfig(String key, Object value) {
		config.put(key, value);
	}

	/**
	 * 지금까지 모은 기록을 저장할 형태로 굳힌다. 끝나지 않은 단계는 지금 시각에 끝난 것으로 닫고 history에 중단됐다는 오류를 남긴다.
	 *
	 * @param appVersion 이 기록을 만든 앱 버전. 알 수 없으면 null
	 */
	public synchronized TraceRecord snapshot(String appVersion) {
		LocalDateTime now = LocalDateTime.now(clock);
		List<SpanRecord> records = new ArrayList<>();
		for (Span span : spans) {
			if (span.endedAt == null) {
				Map<String, Object> history = new LinkedHashMap<>(span.history);
				history.put("error", Map.of("type", "Interrupted", "message", "단계가 끝나기 전에 처리가 중단됨"));
				records.add(new SpanRecord(span.type, span.startedAt, now, history));
			} else {
				records.add(new SpanRecord(span.type, span.startedAt, span.endedAt, span.history));
			}
		}
		return new TraceRecord(messageId, appVersion, new LinkedHashMap<>(config), startedAt, List.copyOf(records));
	}

	/** 단계 하나. {@link #end} 또는 {@link #fail}을 한 번만 반영한다(두 번째부터는 무시). */
	public final class Span {

		private final SpanType type;
		private final LocalDateTime startedAt;
		private LocalDateTime endedAt;
		private Map<String, Object> history = new LinkedHashMap<>();

		private Span(SpanType type, LocalDateTime startedAt) {
			this.type = type;
			this.startedAt = startedAt;
		}

		/** 단계를 정상으로 끝내며 그 단계의 원문 스냅샷을 남긴다. */
		public void end(Map<String, Object> historyToKeep) {
			synchronized (TraceCollector.this) {
				if (endedAt != null) {
					return;
				}
				history = historyToKeep == null ? new LinkedHashMap<>() : new LinkedHashMap<>(historyToKeep);
				endedAt = LocalDateTime.now(clock);
			}
		}

		/** 단계를 오류로 끝낸다. 지금까지 모은 스냅샷에 {@code error}(예외 종류와 메시지)를 더해 남긴다. */
		public void fail(Throwable cause, Map<String, Object> historyToKeep) {
			synchronized (TraceCollector.this) {
				if (endedAt != null) {
					return;
				}
				Map<String, Object> kept = historyToKeep == null ? new LinkedHashMap<>() : new LinkedHashMap<>(historyToKeep);
				String message = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
				kept.put("error", Map.of("type", cause == null ? "Unknown" : cause.getClass().getSimpleName(), "message", message));
				history = kept;
				endedAt = LocalDateTime.now(clock);
			}
		}
	}
}
