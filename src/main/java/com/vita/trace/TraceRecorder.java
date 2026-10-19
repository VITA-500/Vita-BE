package com.vita.trace;

import java.time.Clock;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 질문 1건의 처리 기록(Trace)을 시작하고 저장하는 진입점.
 *
 * <p>사용법은 {@link #start}로 모으는 도구({@link TraceCollector})를 받아 단계마다 기록하고, 답변 흐름이 끝나면
 * {@link #saveAsync}로 저장을 맡긴다. 저장은 답변 흐름과 분리된 실행기에서 하며, 저장에 실패해도(저장소 예외, 실행기 포화) 예외를
 * 밖으로 던지지 않고 로그만 남겨 답변을 막지 않는다. 기록을 끌 때는 {@code trace.enabled=false}로 두면 아무것도 저장하지 않는다.
 */
@Slf4j
@Component
public class TraceRecorder {

	private final TraceRepository repository;
	private final Executor executor;
	private final boolean enabled;
	private final String appVersion;
	private final Clock clock;

	public TraceRecorder(TraceRepository repository, @Qualifier("traceExecutor") Executor executor,
			@Value("${trace.enabled:true}") boolean enabled, @Value("${trace.app-version:}") String appVersion) {
		this(repository, executor, enabled, appVersion, Clock.systemDefaultZone());
	}

	/** 테스트에서 시간을 고정하기 위한 생성자. */
	TraceRecorder(TraceRepository repository, Executor executor, boolean enabled, String appVersion, Clock clock) {
		this.repository = repository;
		this.executor = executor;
		this.enabled = enabled;
		this.appVersion = appVersion == null || appVersion.isBlank() ? null : appVersion;
		this.clock = clock;
	}

	/** 기록을 켜 두었는지. 꺼져 있으면 호출하는 쪽도 history를 만들 필요가 없다. */
	public boolean enabled() {
		return enabled;
	}

	/**
	 * 질문 1건의 기록을 모으기 시작한다.
	 *
	 * @param messageId 답변 메시지 id(찾기용 값)
	 */
	public TraceCollector start(Long messageId) {
		return new TraceCollector(messageId, clock);
	}

	/**
	 * 지금까지 모은 기록을 저장하도록 맡긴다. 기록 시점의 상태를 먼저 굳히고(스냅샷) 저장은 별도 실행기에서 한다.
	 * 기록이 꺼져 있거나 messageId가 없으면 아무것도 하지 않는다.
	 */
	public void saveAsync(TraceCollector collector) {
		if (!enabled || collector == null) {
			return;
		}
		try {
			TraceRecord record = collector.snapshot(appVersion);
			if (record.messageId() == null) {
				return;
			}
			executor.execute(() -> persist(record));
		} catch (RuntimeException e) {
			log.warn("trace 저장을 맡기지 못했습니다(답변에는 영향 없음). 원인={}", e.getClass().getSimpleName());
		}
	}

	/** 저장소에 넣는다. 실패해도 예외를 밖으로 던지지 않는다. 질문 원문이 로그에 남지 않도록 message id만 적는다. */
	void persist(TraceRecord record) {
		try {
			repository.save(record);
		} catch (Exception e) {
			log.warn("trace 저장 실패(답변에는 영향 없음). messageId={}, 원인={}", record.messageId(), e.getClass().getSimpleName());
		}
	}
}
