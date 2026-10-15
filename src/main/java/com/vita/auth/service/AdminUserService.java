package com.vita.auth.service;

import com.vita.auth.PasswordPolicy;
import com.vita.auth.Role;
import com.vita.auth.UserStatus;
import com.vita.auth.dto.*;
import com.vita.auth.entity.User;
import com.vita.auth.entity.UserAuditLog;
import com.vita.auth.repository.*;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 변경 정책을 검증하고 유저 변경과 감사 로그를 같은 트랜잭션에 저장한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {
    private final UserRepository users;
    private final UserAuditLogRepository auditLogs;
    private final PasswordEncoder passwordEncoder;
    private static final Set<String> SORT_FIELDS = Set.of("id", "email", "name", "createdAt", "updatedAt");

    /** 기본 목록은 탈퇴 계정을 제외한다. 정렬 필드와 페이지 크기는 허용 범위로 제한한다. */
    public PageResponse<AdminUserListResponse> list(PageRequest request, Role role, UserStatus status) {
        validatePage(request);
        var pageable = request.toSpringPageRequest(SORT_FIELDS, "createdAt,desc");
        pageable = org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
            pageable.getSort().and(org.springframework.data.domain.Sort.by("id")));
        return PageResponse.from(users.findAll(UserSpecs.search(request.keyword(), role, status), pageable), AdminUserListResponse::from);
    }

    /** 대상 계정을 조회한다. 없는 ID는 USER_NOT_FOUND로 응답한다. */
    public AdminUserResponse get(Long id) { return AdminUserResponse.from(find(id)); }

    /** 대상 계정의 변경 이력을 페이지로 반환한다. 탈퇴 계정의 이력도 조회할 수 있다. */
    public PageResponse<UserAuditResponse> audit(Long id, PageRequest request) {
        find(id);
        validatePage(request);
        return PageResponse.from(auditLogs.findByTargetId(id,
            request.toSpringPageRequest(Set.of("id", "createdAt"), "id,desc")), UserAuditResponse::from);
    }

    /** 비밀번호 정책과 이메일 중복을 검사하고 해싱한 비밀번호로 USER/ACTIVE 계정을 생성한다. */
    @Transactional
    public AdminUserResponse create(AdminUserCreateRequest request, Long actorId) {
        authorizeMutation(actorId);
        PasswordPolicy.validate(request.password());
        if (users.existsByEmail(request.email())) throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        User user = User.ofLocal(request.email(), passwordEncoder.encode(request.password()), request.name().trim());
        user.changePhone(normalizePhone(request.phone()));
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 사전 검사와 INSERT 사이에 일반 회원가입이 들어와도 중복 이메일은 409로 처리한다.
            if (hasEmailConstraint(e)) throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
            throw e;
        }
        record(actorId, user, "CREATE", request.reason(), "email,name,phone", null);
        return AdminUserResponse.from(user);
    }

    /** null 필드는 유지하고 빈 전화번호는 제거한다. 실제 변경된 필드만 감사 로그에 기록한다. */
    @Transactional
    public AdminUserResponse update(Long id, AdminUserUpdateRequest request, Long actorId) {
        authorizeMutation(actorId);
        User user = mutable(id);
        String before = UserAuditLog.stateOf(user);
        var fields = new ArrayList<String>();
        if (request.name() != null && !Objects.equals(user.getName(), request.name().trim())) {
            user.changeName(request.name().trim()); fields.add("name");
        }
        if (request.phone() != null && !Objects.equals(user.getPhone(), normalizePhone(request.phone()))) {
            user.changePhone(normalizePhone(request.phone())); fields.add("phone");
        }
        if (request.name() == null && request.phone() == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (!fields.isEmpty()) record(actorId, user, "UPDATE", request.reason(), String.join(",", fields), before);
        users.flush();
        return AdminUserResponse.from(user);
    }

    /** 자기 계정·마지막 활성 관리자 보호 후 역할을 변경하고 기존 JWT를 무효화한다. */
    @Transactional
    public AdminUserResponse changeRole(Long id, AdminUserRoleRequest request, Long actorId) {
        authorizeMutation(actorId);
        protectSelf(id, actorId);
        User user = mutable(id);
        if (user.getRole() != request.role()) {
            protectLastAdmin(user, request.role(), user.getStatus());
            String before = UserAuditLog.stateOf(user);
            user.changeRole(request.role());
            record(actorId, user, "ROLE_CHANGE", request.reason(), "role", before);
        }
        users.flush();
        return AdminUserResponse.from(user);
    }

    /** 정지/해제만 허용한다. 탈퇴 계정은 복구할 수 없으며 변경 시 기존 JWT를 무효화한다. */
    @Transactional
    public AdminUserResponse changeStatus(Long id, AdminUserStatusRequest request, Long actorId) {
        if (request.status() == UserStatus.DELETED) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "탈퇴는 DELETE API를 사용하세요.");
        authorizeMutation(actorId);
        protectSelf(id, actorId);
        User user = mutable(id);
        setStatus(user, request.status(), request.reason(), actorId, "STATUS_CHANGE");
        users.flush();
        return AdminUserResponse.from(user);
    }

    /** 행을 지우지 않고 탈퇴 처리한다. 반복 삭제는 토큰 버전과 감사 로그를 중복 변경하지 않는다. */
    @Transactional
    public AdminUserResponse delete(Long id, AdminUserDeleteRequest request, Long actorId) {
        authorizeMutation(actorId);
        protectSelf(id, actorId);
        User user = users.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        setStatus(user, UserStatus.DELETED, request.reason(), actorId, "DELETE");
        users.flush();
        return AdminUserResponse.from(user);
    }

    private void setStatus(User user, UserStatus status, String reason, Long actorId, String action) {
        if (user.getStatus() == status) return;
        protectLastAdmin(user, user.getRole(), status);
        String before = UserAuditLog.stateOf(user);
        user.changeStatus(status);
        record(actorId, user, action, reason, "status", before);
    }

    /** 관리자 변경을 직렬화한 뒤 실행자의 현재 권한과 활성 상태를 잠금 안에서 재검사한다. */
    private void authorizeMutation(Long actorId) {
        users.lockManagement();
        User actor = users.findByIdForUpdate(actorId).orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (!actor.isActive() || actor.getRole() != Role.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private User find(Long id) {
        return users.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    /** 수정 대상 행을 잠그고 탈퇴 계정의 변경을 거부한다. */
    private User mutable(Long id) {
        User user = users.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() == UserStatus.DELETED) throw new BusinessException(ErrorCode.USER_DELETED);
        return user;
    }

    private void protectSelf(Long id, Long actorId) {
        if (id.equals(actorId)) throw new BusinessException(ErrorCode.SELF_ACCOUNT_PROTECTED);
    }

    /** 강등·정지·삭제로 마지막 활성 관리자가 사라지는 것을 막는다. 공통 잠금 획득 후 호출한다. */
    private void protectLastAdmin(User user, Role nextRole, UserStatus nextStatus) {
        if (user.getRole() == Role.ADMIN && user.isActive() && (nextRole != Role.ADMIN || nextStatus != UserStatus.ACTIVE)
            && users.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new BusinessException(ErrorCode.LAST_ADMIN_PROTECTED);
        }
    }

    /** 개인정보 원문을 복제하지 않고 변경 필드와 역할·상태의 전후 값을 기록한다. */
    private void record(Long actorId, User user, String action, String reason, String fields, String before) {
        auditLogs.save(new UserAuditLog(actorId, user, action, reason.trim(), fields, before));
    }

    private static String normalizePhone(String phone) { return phone == null || phone.isBlank() ? null : phone.trim(); }

    private static void validatePage(PageRequest request) {
        if (request.page() < 0 || request.size() < 1 || request.size() > 100
            || (request.keyword() != null && request.keyword().length() > 255)) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (request.sortBy() != null && !request.sortBy().isBlank()
            && !request.sortBy().matches("[a-zA-Z]+(,(?i:asc|desc))?")) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }

    private static boolean hasEmailConstraint(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("uk_users_email")) return true;
        }
        return false;
    }
}
