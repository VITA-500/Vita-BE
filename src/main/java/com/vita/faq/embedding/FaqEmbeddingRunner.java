package com.vita.faq.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(200)
@ConditionalOnProperty(prefix = "faq.embedding", name = "enabled", havingValue = "true")
public class FaqEmbeddingRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(FaqEmbeddingRunner.class);

	private final FaqEmbeddingService faqEmbeddingService;
	private final int batchSize;

	public FaqEmbeddingRunner(
		FaqEmbeddingService faqEmbeddingService,
		@Value("${faq.embedding.batch-size:100}") int batchSize
	) {
		this.faqEmbeddingService = faqEmbeddingService;
		this.batchSize = batchSize;
	}

	@Override
	public void run(ApplicationArguments args) {
		int count = faqEmbeddingService.embedPendingFaqs(batchSize);
		log.info("FAQ 임베딩 저장 완료: count={}", count);
	}
}
