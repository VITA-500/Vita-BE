package com.vita.plan.service;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.embedding.EmbeddingConstants;
import com.vita.embedding.EmbeddingException;
import com.vita.embedding.EmbeddingProvider;
import com.vita.plan.dto.PlanCreateRequest;
import com.vita.plan.dto.PlanDeleteResponse;
import com.vita.plan.dto.PlanItemResponse;
import com.vita.plan.dto.PlanUpdateRequest;
import com.vita.plan.dto.PlanUpdateResponse;
import com.vita.plan.repository.AdminPlanRepository;
import com.vita.plan.repository.PlanRecord;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 요금제 입력 검증, description 임베딩 생성 및 소프트 삭제를 처리한다. */
@Service
public class AdminPlanService {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "updatedAt", "price");
    private static final Set<String> NETWORK_TYPES = Set.of("LTE", "5G", "LTE_5G");
    private static final Set<String> TARGET_GROUPS = Set.of("GENERAL", "YOUTH", "SENIOR", "KIDS", "TABLET", "WATCH");
    private static final Set<String> DATA_POLICIES = Set.of("LIMITED", "UNLIMITED");
    private static final Set<String> USAGE_POLICIES = Set.of("NONE", "LIMITED", "UNLIMITED");

    private final AdminPlanRepository repository;
    private final EmbeddingProvider embeddingProvider;

    public AdminPlanService(AdminPlanRepository repository, EmbeddingProvider embeddingProvider) {
        this.repository = repository;
        this.embeddingProvider = embeddingProvider;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<PlanItemResponse> list(PageRequest request) {
        validatePaging(request);
        validateSortSyntax(request.sortBy());
        var pageable = request.toSpringPageRequest(SORT_FIELDS, "createdAt,desc");
        var order = pageable.getSort().iterator().next();
        return repository.search(pageable.getPageNumber(), pageable.getPageSize(),
            blankToNull(request.keyword()), order.getProperty(), order.getDirection());
    }

    @Transactional
    public PlanItemResponse create(PlanCreateRequest request) {
        if (request.price() == null) {
            throw invalid("price는 null일 수 없습니다.");
        }
        var plan = from(request);
        validate(plan);
        ensureUniqueCode(plan.planCode(), 0);
        try {
            return repository.create(plan, embed(plan.description()));
        } catch (DuplicateKeyException e) {
            throw duplicateCode();
        }
    }

    @Transactional
    public PlanUpdateResponse update(long id, PlanUpdateRequest request) {
        if (request.getProvided().isEmpty()) {
            throw invalid("수정할 필드를 하나 이상 입력해 주세요.");
        }
        var old = requirePlan(id);
        var plan = merge(old, request);
        validate(plan);
        ensureUniqueCode(plan.planCode(), id);
        float[] vector = Objects.equals(old.description(), plan.description()) ? null : embed(plan.description());
        try {
            return new PlanUpdateResponse(id, repository.update(plan, vector));
        } catch (DuplicateKeyException e) {
            throw duplicateCode();
        }
    }

    @Transactional
    public PlanDeleteResponse delete(long id) {
        requirePlan(id);
        repository.deactivate(id);
        return new PlanDeleteResponse(id, true);
    }

    private PlanRecord from(PlanCreateRequest request) {
        return new PlanRecord(0, normalize(request.planCode()), normalize(request.name()),
            normalize(request.summary()), request.price(), normalize(request.networkType()),
            normalize(request.targetGroup()), request.minAge(), request.maxAge(),
            normalize(request.dataPolicy()), request.baseDataMb(), request.exhaustedSpeedKbps(),
            normalize(request.voicePolicy()), request.voiceMinutes(), normalize(request.smsPolicy()),
            request.smsCount(), normalize(request.description()), "ACTIVE", null, null);
    }

    private PlanRecord merge(PlanRecord old, PlanUpdateRequest request) {
        return new PlanRecord(old.id(),
            request.has("planCode") ? normalize(request.getPlanCode()) : old.planCode(),
            request.has("name") ? normalize(request.getName()) : old.name(),
            request.has("summary") ? normalize(request.getSummary()) : old.summary(),
            request.has("price") && request.getPrice() != null ? request.getPrice() : nullableRequired(request.has("price"), old.price(), "price"),
            request.has("networkType") ? normalize(request.getNetworkType()) : old.networkType(),
            request.has("targetGroup") ? normalize(request.getTargetGroup()) : old.targetGroup(),
            request.has("minAge") ? request.getMinAge() : old.minAge(),
            request.has("maxAge") ? request.getMaxAge() : old.maxAge(),
            request.has("dataPolicy") ? normalize(request.getDataPolicy()) : old.dataPolicy(),
            request.has("baseDataMb") ? request.getBaseDataMb() : old.baseDataMb(),
            request.has("exhaustedSpeedKbps") ? request.getExhaustedSpeedKbps() : old.exhaustedSpeedKbps(),
            request.has("voicePolicy") ? normalize(request.getVoicePolicy()) : old.voicePolicy(),
            request.has("voiceMinutes") ? request.getVoiceMinutes() : old.voiceMinutes(),
            request.has("smsPolicy") ? normalize(request.getSmsPolicy()) : old.smsPolicy(),
            request.has("smsCount") ? request.getSmsCount() : old.smsCount(),
            request.has("description") ? normalize(request.getDescription()) : old.description(),
            request.has("status") ? normalize(request.getStatus()) : old.status(),
            old.createdAt(), old.updatedAt());
    }

    private int nullableRequired(boolean provided, int current, String field) {
        if (provided) {
            throw invalid(field + "는 null일 수 없습니다.");
        }
        return current;
    }

    private PlanRecord requirePlan(long id) {
        if (id < 1) {
            throw invalid("planId는 양수여야 합니다.");
        }
        return repository.findForUpdate(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "요금제를 찾을 수 없습니다."));
    }

    private void ensureUniqueCode(String planCode, long excludedId) {
        if (repository.existsByPlanCodeExcludingId(planCode, excludedId)) {
            throw duplicateCode();
        }
    }

    private void validate(PlanRecord plan) {
        requireText(plan.planCode(), "planCode", 50);
        requireText(plan.name(), "name", 100);
        requireText(plan.summary(), "summary", 255);
        requireText(plan.description(), "description", Integer.MAX_VALUE);
        requireMember(NETWORK_TYPES, plan.networkType(), "networkType");
        requireMember(TARGET_GROUPS, plan.targetGroup(), "targetGroup");
        requireMember(DATA_POLICIES, plan.dataPolicy(), "dataPolicy");
        requireMember(USAGE_POLICIES, plan.voicePolicy(), "voicePolicy");
        requireMember(USAGE_POLICIES, plan.smsPolicy(), "smsPolicy");
        requireMember(Set.of("ACTIVE", "INACTIVE"), plan.status(), "status");
        if (plan.price() < 0) {
            throw invalid("price는 0 이상이어야 합니다.");
        }
        validateAge(plan.minAge(), plan.maxAge());
        validateData(plan);
        validateUsage("voice", plan.voicePolicy(), plan.voiceMinutes());
        validateUsage("sms", plan.smsPolicy(), plan.smsCount());
    }

    private void validateAge(Integer minAge, Integer maxAge) {
        if ((minAge != null && (minAge < 0 || minAge > 150))
                || (maxAge != null && (maxAge < 0 || maxAge > 150))
                || (minAge != null && maxAge != null && minAge > maxAge)) {
            throw invalid("가입 연령은 0~150 범위이고 minAge는 maxAge 이하여야 합니다.");
        }
    }

    private void validateData(PlanRecord plan) {
        if ("LIMITED".equals(plan.dataPolicy())) {
            if (plan.baseDataMb() == null || plan.baseDataMb() < 1) {
                throw invalid("LIMITED 데이터 요금제는 baseDataMb가 필요합니다.");
            }
            if (plan.exhaustedSpeedKbps() != null && plan.exhaustedSpeedKbps() < 1) {
                throw invalid("exhaustedSpeedKbps는 1 이상이어야 합니다.");
            }
        } else if (plan.baseDataMb() != null || plan.exhaustedSpeedKbps() != null) {
            throw invalid("UNLIMITED 데이터 요금제는 데이터량과 소진 후 속도를 지정할 수 없습니다.");
        }
    }

    private void validateUsage(String field, String policy, Integer amount) {
        if ("LIMITED".equals(policy)) {
            if (amount == null || amount < 1) {
                throw invalid("LIMITED " + field + " 정책은 제공량이 필요합니다.");
            }
        } else if (amount != null) {
            throw invalid(field + " 제공량은 LIMITED 정책에서만 지정할 수 있습니다.");
        }
    }

    private void validatePaging(PageRequest request) {
        if (request.page() < 0 || request.size() < 1 || request.size() > 100) {
            throw invalid("page는 0 이상, size는 1~100이어야 합니다.");
        }
    }

    private void validateSortSyntax(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return;
        }
        String[] parts = sortBy.split(",", -1);
        if (parts.length != 2
                || !("asc".equalsIgnoreCase(parts[1].trim()) || "desc".equalsIgnoreCase(parts[1].trim()))) {
            throw invalid("sortBy는 {createdAt|updatedAt|price},{asc|desc} 형식이어야 합니다.");
        }
    }

    private float[] embed(String description) {
        try {
            float[] vector = embeddingProvider.embedDocument(description);
            if (vector == null || vector.length != EmbeddingConstants.DIMENSIONS) {
                throw new EmbeddingException("잘못된 임베딩 차원");
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new EmbeddingException("유효하지 않은 임베딩 값");
                }
            }
            return vector;
        } catch (EmbeddingException e) {
            throw new BusinessException(ErrorCode.PLAN_EMBEDDING_FAILED);
        }
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + " 값이 올바르지 않습니다.");
        }
    }

    private void requireMember(Set<String> allowed, String value, String field) {
        if (value == null || !allowed.contains(value)) {
            throw invalid("지원하지 않는 " + field + " 값입니다.");
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.strip();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    private BusinessException duplicateCode() {
        return new BusinessException(ErrorCode.PLAN_CODE_ALREADY_EXISTS);
    }
}
