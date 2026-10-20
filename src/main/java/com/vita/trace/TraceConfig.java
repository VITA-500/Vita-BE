package com.vita.trace;

import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * trace 저장 전용 실행기. 답변 생성 실행기와 분리해서, 저장이 밀려도 답변 처리에 쓸 스레드를 빼앗지 않게 한다.
 * 대기열이 가득 차면 새 기록을 버리고 로그만 남긴다(기록은 부가 기능이라 답변보다 우선하지 않는다).
 */
@Slf4j
@Configuration
public class TraceConfig {

	@Bean(name = "traceExecutor")
	public Executor traceExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("trace-save-");
		executor.setRejectedExecutionHandler((task, pool) -> log.warn("trace 저장 대기열이 가득 차 기록 하나를 버렸습니다."));
		executor.initialize();
		return executor;
	}
}
