package com.vita.faq.embedding;

import com.vita.embedding.EmbeddingProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FaqEmbeddingService {

	private final EmbeddingProvider embeddingProvider;
	private final FaqEmbeddingRepository faqEmbeddingRepository;

	public FaqEmbeddingService(
		EmbeddingProvider embeddingProvider,
		FaqEmbeddingRepository faqEmbeddingRepository
	) {
		this.embeddingProvider = embeddingProvider;
		this.faqEmbeddingRepository = faqEmbeddingRepository;
	}

	public int embedPendingFaqs(int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize는 1 이상이어야 합니다.");
		}

		int savedCount = 0;
		while (true) {
			List<FaqEmbeddingTarget> targets = faqEmbeddingRepository.findPending(batchSize);
			if (targets.isEmpty()) {
				return savedCount;
			}

			int savedInBatch = 0;
			for (FaqEmbeddingTarget target : targets) {
				float[] vector = embeddingProvider.embedDocument(target.toEmbeddingText());
				if (faqEmbeddingRepository.saveIfPending(target.id(), vector)) {
					savedCount++;
					savedInBatch++;
				}
			}

			if (targets.size() < batchSize || savedInBatch == 0) {
				return savedCount;
			}
		}
	}
}
