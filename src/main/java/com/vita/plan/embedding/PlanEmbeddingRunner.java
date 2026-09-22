package com.vita.plan.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(210)
@ConditionalOnProperty(prefix = "plan.embedding", name = "enabled", havingValue = "true")
public class PlanEmbeddingRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PlanEmbeddingRunner.class);
    private final PlanEmbeddingService service;
    private final int batchSize;

    public PlanEmbeddingRunner(PlanEmbeddingService service,
                              @Value("${plan.embedding.batch-size:100}") int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("요금제 임베딩 저장 완료: count={}", service.embedPendingPlans(batchSize));
    }
}
