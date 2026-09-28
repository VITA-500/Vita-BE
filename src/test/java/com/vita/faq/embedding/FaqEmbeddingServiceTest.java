package com.vita.faq.embedding;

import com.vita.embedding.EmbeddingConstants;
import com.vita.embedding.EmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FaqEmbeddingServiceTest {

	@Test
	void embedsQuestionAndAnswerAndSavesVector() {
		EmbeddingProvider provider = mock(EmbeddingProvider.class);
		FaqEmbeddingRepository repository = mock(FaqEmbeddingRepository.class);
		FaqEmbeddingService service = new FaqEmbeddingService(provider, repository);
		FaqEmbeddingTarget target = new FaqEmbeddingTarget(1L, "질문 내용", "답변 내용");
		float[] vector = new float[EmbeddingConstants.DIMENSIONS];

		when(repository.findPending(100))
			.thenReturn(List.of(target))
			.thenReturn(List.of());
		when(provider.embedDocument("질문: 질문 내용\n답변: 답변 내용")).thenReturn(vector);
		when(repository.saveIfPending(1L, vector)).thenReturn(true);

		int count = service.embedPendingFaqs(100);

		assertThat(count).isEqualTo(1);
		verify(provider).embedDocument("질문: 질문 내용\n답변: 답변 내용");
		verify(repository).saveIfPending(1L, vector);
	}
}
