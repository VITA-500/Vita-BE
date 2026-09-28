package com.vita.plan.embedding;

import com.vita.embedding.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlanEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(PlanEmbeddingService.class);
    private final EmbeddingProvider embeddingProvider;
    private final PlanEmbeddingRepository repository;

    public PlanEmbeddingService(EmbeddingProvider embeddingProvider, PlanEmbeddingRepository repository) {
        this.embeddingProvider = embeddingProvider;
        this.repository = repository;
    }

    // 외부 모델 호출 동안 DB 트랜잭션을 열지 않는다. 저장은 행마다 원자적으로 처리한다.
    public int embedPendingPlans(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize는 1 이상이어야 합니다.");
        }
        int savedCount = 0;
        long afterId = 0;
        while (true) {
            var targets = repository.findPending(afterId, batchSize);
            if (targets.isEmpty()) {
                return savedCount;
            }
            for (var target : targets) {
                float[] vector = embeddingProvider.embedDocument(target.description());
                if (repository.saveIfPending(target, vector)) {
                    savedCount++;
                } else {
                    log.warn("요금제 임베딩 저장 건너뜀: id={}, 설명·상태 변경 또는 다른 작업에서 저장됨", target.id());
                }
                afterId = target.id();
            }
            if (targets.size() < batchSize) {
                return savedCount;
            }
        }
    }
}
