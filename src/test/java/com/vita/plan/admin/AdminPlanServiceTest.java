package com.vita.plan.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.embedding.EmbeddingException;
import com.vita.embedding.EmbeddingProvider;
import com.vita.plan.dto.PlanCreateRequest;
import com.vita.plan.dto.PlanDeleteResponse;
import com.vita.plan.dto.PlanUpdateRequest;
import com.vita.plan.repository.AdminPlanRepository;
import com.vita.plan.repository.PlanRecord;
import com.vita.plan.service.AdminPlanService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class AdminPlanServiceTest {

    private final AdminPlanRepository repository = mock(AdminPlanRepository.class);
    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);
    private final AdminPlanService service = new AdminPlanService(repository, provider);
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlanRecord old = record("기존 설명", "ACTIVE");

    @Test
    void listUsesDefaultAndAllowedSortAndRejectsInvalidRequests() {
        service.list(PageRequest.of(null, null, " 5G ", null));
        verify(repository).search(0, 20, "5G", "createdAt", Sort.Direction.DESC);

        service.list(PageRequest.of(1, 10, null, "price,asc"));
        verify(repository).search(1, 10, null, "price", Sort.Direction.ASC);

        service.list(PageRequest.of(0, 20, null, "updatedAt,desc"));
        verify(repository).search(0, 20, null, "updatedAt", Sort.Direction.DESC);

        for (var request : new PageRequest[]{
            PageRequest.of(-1, 20, null, null), PageRequest.of(0, 0, null, null),
            PageRequest.of(0, 101, null, null), PageRequest.of(0, 20, null, "name,asc"),
            PageRequest.of(0, 20, null, "price,sideways")}) {
            assertThatThrownBy(() -> service.list(request)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void createAlwaysActiveAndEmbedsDescription() {
        var vector = new float[768];
        when(provider.embedDocument("설명")).thenReturn(vector);
        service.create(createRequest("VITA-NEW", "설명"));
        verify(repository).create(record("VITA-NEW", "설명", "ACTIVE"), vector);
    }

    @Test
    void duplicateCodeAndEmbeddingFailureNeverWrite() {
        var request = createRequest("VITA-NEW", "설명");
        when(repository.existsByPlanCodeExcludingId("VITA-NEW", 0)).thenReturn(true);
        assertThatThrownBy(() -> service.create(request)).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PLAN_CODE_ALREADY_EXISTS));
        verify(repository, never()).create(any(), any());

        when(repository.existsByPlanCodeExcludingId("VITA-NEW", 0)).thenReturn(false);
        when(provider.embedDocument("설명")).thenThrow(new EmbeddingException("private backend error"));
        assertThatThrownBy(() -> service.create(request)).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PLAN_EMBEDDING_FAILED));
        verify(repository, never()).create(any(), any());
    }

    @Test
    void descriptionChangeReembedsAndStatusOnlyKeepsVector() throws Exception {
        when(repository.findForUpdate(1)).thenReturn(Optional.of(old));
        when(repository.update(any(), any())).thenReturn(LocalDateTime.of(2026, 9, 30, 12, 0));
        var vector = new float[768];
        when(provider.embedDocument("새 설명")).thenReturn(vector);
        service.update(1, patch("{\"description\":\"새 설명\"}"));
        verify(repository).update(record("새 설명", "ACTIVE"), vector);

        service.update(1, patch("{\"status\":\"INACTIVE\"}"));
        verify(repository).update(record("기존 설명", "INACTIVE"), null);
    }

    @Test
    void optionalFieldsCanBeClearedButRequiredFieldsCannot() throws Exception {
        when(repository.findForUpdate(1)).thenReturn(Optional.of(old));
        when(repository.update(any(), any())).thenReturn(LocalDateTime.now());
        service.update(1, patch("{\"minAge\":null,\"maxAge\":null}"));
        verify(repository).update(any(), eq(null));

        for (String body : new String[]{"{}", "{\"name\":null}", "{\"price\":null}",
                "{\"status\":null}", "{\"networkType\":\"3G\"}"}) {
            assertThatThrownBy(() -> service.update(1, patch(body))).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void validatesConditionalUsageFields() {
        assertThatThrownBy(() -> service.create(new PlanCreateRequest("CODE", "이름", "요약", null,
            "5G", "GENERAL", null, null, "LIMITED", 100L, null,
            "UNLIMITED", null, "UNLIMITED", null, "설명"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(new PlanCreateRequest("CODE", "이름", "요약", 1000,
            "5G", "GENERAL", null, null, "UNLIMITED", 100L, null,
            "UNLIMITED", null, "UNLIMITED", null, "설명"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(new PlanCreateRequest("CODE", "이름", "요약", 1000,
            "5G", "GENERAL", null, null, "LIMITED", 100L, null,
            "LIMITED", null, "UNLIMITED", null, "설명"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(provider);
    }

    @Test
    void deleteIsIdempotentSoftDeleteAndMissingPlanIs404() {
        assertThatThrownBy(() -> service.delete(1)).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        when(repository.findForUpdate(1)).thenReturn(Optional.of(old));
        assertThat(service.delete(1)).isEqualTo(new PlanDeleteResponse(1, true));
        verify(repository).deactivate(1);
        verifyNoInteractions(provider);
    }

    private PlanUpdateRequest patch(String json) throws Exception {
        return mapper.readValue(json, PlanUpdateRequest.class);
    }

    private PlanCreateRequest createRequest(String code, String description) {
        return new PlanCreateRequest(code, "요금제", "요약", 39000, "5G", "GENERAL",
            null, null, "LIMITED", 6144L, null, "UNLIMITED", null,
            "UNLIMITED", null, description);
    }

    private PlanRecord record(String description, String status) {
        return record("VITA-OLD", description, status);
    }

    private PlanRecord record(String code, String description, String status) {
        boolean isNew = "VITA-NEW".equals(code);
        return new PlanRecord(isNew ? 0 : 1, code, "요금제", "요약", 39000, "5G", "GENERAL",
            null, null, "LIMITED", 6144L, null, "UNLIMITED", null,
            "UNLIMITED", null, description, status,
            isNew ? null : LocalDateTime.of(2026, 9, 1, 0, 0), null);
    }
}
