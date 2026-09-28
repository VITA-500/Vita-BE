package com.vita.faq.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.embedding.EmbeddingException;
import com.vita.embedding.EmbeddingProvider;
import com.vita.faq.dto.*;
import com.vita.faq.repository.*;
import com.vita.faq.service.AdminFaqService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdminFaqServiceTest {
    private final AdminFaqRepository repository = mock(AdminFaqRepository.class);
    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);
    private final AdminFaqService service = new AdminFaqService(repository, provider);
    private final ObjectMapper mapper = new ObjectMapper();
    private final FaqRecord old = new FaqRecord(1, "모바일", "요금제", "기존 질문", "기존 답변", "ACTIVE");

    private FaqUpdateRequest patch(String json) throws Exception { return mapper.readValue(json, FaqUpdateRequest.class); }
    private void existing() {
        when(repository.findForUpdate(1)).thenReturn(Optional.of(old));
        when(repository.update(any(), any(), anyLong())).thenReturn(LocalDateTime.of(2026, 9, 23, 10, 0));
    }

    @Test void createEmbedsBothFieldsAndAlwaysActive() {
        var vector = new float[768];
        when(provider.embedDocument("질문: 질문\n답변: 답변")).thenReturn(vector);
        service.create(new FaqCreateRequest("모바일", null, "질문", "답변"), 9);
        verify(repository).create(new FaqRecord(0, "모바일", null, "질문", "답변", "ACTIVE"), vector, 9);
    }

    @Test void statusOnlyPreservesTextAndDoesNotEmbed() throws Exception {
        existing();
        service.update(1, patch("{\"status\":\"INACTIVE\"}"), 9);
        verify(repository).update(new FaqRecord(1,"모바일","요금제","기존 질문","기존 답변","INACTIVE"), null, 9);
        verifyNoInteractions(provider);
    }

    @Test void explicitNullClearsSubcategoryAndSameTextDoesNotEmbed() throws Exception {
        existing();
        service.update(1, patch("{\"subcategory\":null,\"question\":\"기존 질문\"}"), 9);
        verify(repository).update(new FaqRecord(1,"모바일",null,"기존 질문","기존 답변","ACTIVE"), null, 9);
        verifyNoInteractions(provider);
    }

    @Test void changedAnswerEmbedsMergedTextEvenWhenInactive() throws Exception {
        when(repository.findForUpdate(1)).thenReturn(Optional.of(new FaqRecord(1,"모바일","요금제","기존 질문","기존 답변","INACTIVE")));
        var vector = new float[768];
        when(provider.embedDocument("질문: 기존 질문\n답변: 새 답변")).thenReturn(vector);
        service.update(1, patch("{\"answer\":\"새 답변\"}"), 9);
        verify(repository).update(new FaqRecord(1,"모바일","요금제","기존 질문","새 답변","INACTIVE"), vector, 9);
    }

    @Test void validatesMergedCategoryAndNullRequiredFields() throws Exception {
        existing();
        for (String body : new String[]{"{\"category\":\"해외로밍\"}", "{\"answer\":null}", "{\"status\":null}", "{\"question\":\" \"}", "{}"}) {
            var request = patch(body);
            assertThatThrownBy(() -> service.update(1, request, 9)).isInstanceOf(BusinessException.class);
        }
        verify(repository, never()).update(any(), any(), anyLong());
        verifyNoInteractions(provider);
    }

    @Test void failedOrMalformedEmbeddingNeverWrites() {
        var request = new FaqCreateRequest("모바일",null,"질문","답변");
        when(provider.embedDocument(anyString())).thenThrow(new EmbeddingException("private backend error"));
        assertThatThrownBy(() -> service.create(request,9)).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FAQ_EMBEDDING_FAILED));
        reset(provider);
        when(provider.embedDocument(anyString())).thenReturn(new float[3]);
        assertThatThrownBy(() -> service.create(request,9)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }

    @Test void missingFaqIs404AndDeleteUsesOnlyDeactivation() {
        assertThatThrownBy(() -> service.delete(1,9)).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        existing();
        assertThat(service.delete(1,9)).isEqualTo(new FaqDeleteResponse(1,true));
        verify(repository).deactivate(1,9);
        verifyNoInteractions(provider);
    }

    @Test void changedCategoryPairKeepsEmbedding() throws Exception {
        existing();
        service.update(1, patch("{\"category\":\"해외로밍\",\"subcategory\":\"서비스안내\"}"), 9);
        verify(repository).update(new FaqRecord(1,"해외로밍","서비스안내","기존 질문","기존 답변","ACTIVE"),null,9);
        verifyNoInteractions(provider);
    }

    @Test void updateModelFailureLeavesRepositoryUntouched() throws Exception {
        existing();
        when(provider.embedDocument(anyString())).thenThrow(new EmbeddingException("failure"));
        var request = patch("{\"answer\":\"수정 답변\"}");
        assertThatThrownBy(() -> service.update(1,request,9)).isInstanceOf(BusinessException.class);
        verify(repository,never()).update(any(),any(),anyLong());
    }
    @Test void listNormalizesBlankFiltersAndRejectsInvalidPaging() {
        service.list(PageRequest.of(0,20," ",null),"", " ");
        verify(repository).search(0,20,null,null,null);
        for (int size : new int[]{0,101}) {
            assertThatThrownBy(() -> service.list(PageRequest.of(0,size,null,null),null,null)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.list(PageRequest.of(-1,20,null,null),null,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(PageRequest.of(0,20,null,null),null,"OTHER")).isInstanceOf(BusinessException.class);
    }
}
