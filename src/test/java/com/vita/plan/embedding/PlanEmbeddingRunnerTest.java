package com.vita.plan.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanEmbeddingRunnerTest {
    private final PlanEmbeddingService service = mock(PlanEmbeddingService.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
        .withBean(PlanEmbeddingService.class, () -> service)
        .withUserConfiguration(PlanEmbeddingRunner.class);

    @Test
    void disabledByDefault() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(PlanEmbeddingRunner.class));
    }

    @Test
    void usesConfiguredBatchSizeWhenEnabled() {
        contextRunner.withPropertyValues("plan.embedding.enabled=true", "plan.embedding.batch-size=7")
            .run(context -> {
                assertThat(context).hasSingleBean(PlanEmbeddingRunner.class);
                context.getBean(PlanEmbeddingRunner.class).run(null);
                verify(service).embedPendingPlans(7);
            });
    }
}
