package com.vita.plan.embedding;

import com.vita.embedding.EmbeddingException;
import com.vita.embedding.EmbeddingProvider;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanEmbeddingServiceTest {
    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);
    private final PlanEmbeddingRepository repository = mock(PlanEmbeddingRepository.class);
    private final PlanEmbeddingService service = new PlanEmbeddingService(provider, repository);

    @Test
    void embedsFullDescriptionAndContinuesAfterConcurrentSkip() {
        var first = new PlanEmbeddingTarget(1, "첫 요금제 전체 설명");
        var second = new PlanEmbeddingTarget(2, "다음 요금제 전체 설명");
        float[] vector = new float[768];
        when(repository.findPending(0, 1)).thenReturn(List.of(first));
        when(repository.findPending(1, 1)).thenReturn(List.of(second));
        when(repository.findPending(2, 1)).thenReturn(List.of());
        when(provider.embedDocument(anyString())).thenReturn(vector);
        when(repository.saveIfPending(first, vector)).thenReturn(false);
        when(repository.saveIfPending(second, vector)).thenReturn(true);
        assertThat(service.embedPendingPlans(1)).isEqualTo(1);
        verify(provider).embedDocument(first.description());
        verify(provider).embedDocument(second.description());
    }

    @Test
    void emptyPendingSetDoesNotCallModel() {
        assertThat(service.embedPendingPlans(100)).isZero();
        verifyNoInteractions(provider);
    }

    @Test
    void invalidBatchSizeFailsBeforeReadingDatabase() {
        assertThatThrownBy(() -> service.embedPendingPlans(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.embedPendingPlans(-1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, provider);
    }

    @Test
    void failedModelCallIsNotSavedAndFailurePropagates() {
        var target = new PlanEmbeddingTarget(1, "설명");
        when(repository.findPending(0, 100)).thenReturn(List.of(target));
        when(provider.embedDocument("설명")).thenThrow(new EmbeddingException("모델 장애"));
        assertThatThrownBy(() -> service.embedPendingPlans(100)).isInstanceOf(EmbeddingException.class);
        verify(repository, never()).saveIfPending(any(), any());
    }
}
