package com.vita.faq.service;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.embedding.EmbeddingConstants;
import com.vita.embedding.EmbeddingException;
import com.vita.embedding.EmbeddingProvider;
import com.vita.faq.dto.*;
import com.vita.faq.embedding.FaqEmbeddingTarget;
import com.vita.faq.repository.AdminFaqRepository;
import com.vita.faq.repository.FaqRecord;
import com.vita.faq.taxonomy.FaqTaxonomy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** FAQ 입력 검증, 임베딩 생성 및 소프트 삭제를 처리 */
@Service
public class AdminFaqService {
    private final AdminFaqRepository repository;
    private final EmbeddingProvider embeddingProvider;

    public AdminFaqService(AdminFaqRepository repository, EmbeddingProvider embeddingProvider) {
        this.repository = repository;
        this.embeddingProvider = embeddingProvider;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<FaqItemResponse> list(PageRequest request, String category, String status) {
        if (request.page() < 0 || request.size() < 1 || request.size() > 100) {
            throw invalid("page는 0 이상, size는 1~100이어야 합니다.");
        }
        category = blankToNull(category);
        status = blankToNull(status);
        if (category != null && !FaqTaxonomy.supports(category)) { throw invalid("지원하지 않는 category입니다."); }
        if (status != null) { validateStatus(status); }
        return repository.search(
            request.page(), request.size(), blankToNull(request.keyword()), category, status);
    }

    @Transactional
    public FaqItemResponse create(FaqCreateRequest request, long adminId) {
        var faq = new FaqRecord(0, request.category(), request.subcategory(), request.question(), request.answer(), "ACTIVE");
        validate(faq);
        return repository.create(faq, embed(faq), adminId);
    }

    @Transactional
    public FaqUpdateResponse update(long id, FaqUpdateRequest request, long adminId) {
        if (request.getProvided().isEmpty()) { throw invalid("수정할 필드를 하나 이상 입력해 주세요."); }
        var old = requireFaq(id);
        var faq = new FaqRecord(id,
            request.has("category") ? request.getCategory() : old.category(),
            request.has("subcategory") ? request.getSubcategory() : old.subcategory(),
            request.has("question") ? request.getQuestion() : old.question(),
            request.has("answer") ? request.getAnswer() : old.answer(),
            request.has("status") ? request.getStatus() : old.status());
        validate(faq);
        boolean changed = !Objects.equals(old.question(), faq.question()) || !Objects.equals(old.answer(), faq.answer());
        // 행 잠금을 보유한 상태에서 본문과 벡터를 함께 갱신한다. 실패하면 변경을 롤백한다.
        float[] vector = changed ? embed(faq) : null;
        return new FaqUpdateResponse(id, repository.update(faq, vector, adminId));
    }

    @Transactional
    public FaqDeleteResponse delete(long id, long adminId) {
        requireFaq(id);
        repository.deactivate(id, adminId);
        return new FaqDeleteResponse(id, true);
    }

    private FaqRecord requireFaq(long id) {
        if (id < 1) { throw invalid("faqId는 양수여야 합니다."); }
        return repository.findForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "FAQ를 찾을 수 없습니다."));
    }

    private void validate(FaqRecord faq) {
        if (!FaqTaxonomy.supports(faq.category())) { throw invalid("지원하지 않는 category입니다."); }
        if (faq.subcategory() != null && !FaqTaxonomy.supports(faq.category(), faq.subcategory())) {
            throw invalid("category에 속하지 않는 subcategory입니다.");
        }
        if (faq.question() == null || faq.question().isBlank() || faq.answer() == null || faq.answer().isBlank()) {
            throw invalid("question과 answer는 비어 있을 수 없습니다.");
        }
        validateStatus(faq.status());
    }

    private void validateStatus(String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) { throw invalid("status는 ACTIVE 또는 INACTIVE여야 합니다."); }
    }

    private float[] embed(FaqRecord faq) {
        try {
            float[] vector = embeddingProvider.embedDocument(new FaqEmbeddingTarget(faq.id(), faq.question(), faq.answer()).toEmbeddingText());
            if (vector == null || vector.length != EmbeddingConstants.DIMENSIONS) { throw new EmbeddingException("잘못된 임베딩 차원"); }
            for (float value : vector) {
                if (!Float.isFinite(value)) { throw new EmbeddingException("유효하지 않은 임베딩 값"); }
            }
            return vector;
        } catch (EmbeddingException e) {
            throw new BusinessException(ErrorCode.FAQ_EMBEDDING_FAILED);
        }
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_ERROR, message); }
}
