package com.vita.trace;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 보관 기간이 지난 trace를 정기적으로 지운다. 질문 원문과 프롬프트가 들어 있어 무기한 쌓아 두지 않기 위한 정리 작업이다.
 *
 * <p>기록을 끈 상태({@code trace.enabled=false})에서도 이미 쌓인 기록은 기간이 지나면 지워야 하므로 기록 설정과 무관하게 돈다.
 * 보관 일수를 0 이하로 두면 정리하지 않는다. 한 번에 많이 지우지 않도록 건수를 나눠서 반복하고, 실패해도 예외를 밖으로
 * 던지지 않고 로그만 남겨 다음 실행 때 이어서 지운다.
 */
@Slf4j
@Component
public class TraceRetentionJob {

	/** 한 번에 지울 건수. 테이블을 오래 잡지 않도록 나눠서 지운다. */
	static final int BATCH_SIZE = 500;

	/** 한 번 실행에서 반복할 최대 횟수. 비정상적으로 많이 쌓여도 한 실행이 끝없이 돌지 않게 하는 안전장치다. */
	static final int MAX_BATCHES = 200;

	private final TraceRepository repository;
	private final int retentionDays;
	private final Clock clock;

	@Autowired
	public TraceRetentionJob(TraceRepository repository, @Value("${trace.retention-days:90}") int retentionDays) {
		this(repository, retentionDays, Clock.systemDefaultZone());
	}

	/** 테스트에서 시간을 고정하기 위한 생성자. */
	TraceRetentionJob(TraceRepository repository, int retentionDays, Clock clock) {
		this.repository = repository;
		this.retentionDays = retentionDays;
		this.clock = clock;
	}

	/** 매일 새벽에 실행한다. 시각은 {@code trace.retention-cron}으로 바꿀 수 있다. */
	@Scheduled(cron = "${trace.retention-cron:0 30 3 * * *}")
	public void purgeExpired() {
		if (retentionDays <= 0) {
			return;
		}
		LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
		int total = 0;
		try {
			for (int i = 0; i < MAX_BATCHES; i++) {
				int deleted = repository.deleteCreatedBefore(cutoff, BATCH_SIZE);
				total += deleted;
				if (deleted < BATCH_SIZE) {
					break;
				}
			}
		} catch (RuntimeException e) {
			log.warn("trace 보관 기간 정리 실패(다음 실행 때 이어서 지움). 지운 건수={}, 원인={}", total, e.getClass().getSimpleName());
			return;
		}
		if (total > 0) {
			log.info("보관 기간({}일)이 지난 trace를 지웠습니다. 건수={}", retentionDays, total);
		}
	}
}
