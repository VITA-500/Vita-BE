package com.vita.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * trace 패키지의 빈들이 스프링 컨텍스트에서 실제로 만들어지는지 확인한다. 생성자를 직접 부르는 단위 테스트로는 "생성자가 둘인 클래스에서
 * 스프링이 어느 쪽을 쓸지 모르는" 문제나 설정값 주입 오류를 잡지 못한다(그 때문에 앱이 시작되지 않은 적이 있다).
 * 정리 작업의 cron 설정이 올바른 형식인지도 스케줄링을 켠 상태로 함께 확인한다.
 */
class TraceWiringTest {

    @Configuration
    @EnableScheduling
    static class Scheduling {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Scheduling.class, TraceConfig.class, TraceRecorder.class, TraceRetentionJob.class)
            .withBean(TraceRepository.class, () -> mock(TraceRepository.class));

    @Test
    void createsTheRecorderAndTheRetentionJobWithDefaultSettings() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TraceRecorder.class).hasSingleBean(TraceRetentionJob.class);
            assertThat(context.getBean("traceExecutor")).isInstanceOf(Executor.class);
            assertThat(context.getBean(TraceRecorder.class).enabled()).isTrue();
        });
    }

    @Test
    void appliesTheSettingsFromProperties() {
        runner.withPropertyValues("trace.enabled=false", "trace.retention-days=30", "trace.retention-cron=0 0 4 * * *")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TraceRecorder.class).enabled()).isFalse();
                });
    }
}
