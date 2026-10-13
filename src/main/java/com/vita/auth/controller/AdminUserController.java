package com.vita.auth.controller;

import com.vita.auth.Role;
import com.vita.auth.UserStatus;
import com.vita.auth.dto.*;
import com.vita.auth.security.UserPrincipal;
import com.vita.auth.service.AdminUserService;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 관리자 유저 조회·생성·수정·탈퇴 API. 변경 실행자는 인증 정보에서 가져온다. */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Tag(name = "관리자 유저 관리")
public class AdminUserController {
    private final AdminUserService service;

    /** 이메일/이름 검색과 역할·상태 필터를 적용한 페이지 목록을 반환한다. */
    @GetMapping
    public PageResponse<AdminUserListResponse> list(@RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size, @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String sortBy, @RequestParam(required = false) Role role,
        @RequestParam(required = false) UserStatus status) {
        return service.list(PageRequest.of(page, size, keyword, sortBy), role, status);
    }

    /** 관리에 필요한 유저 상세 정보를 반환한다. 비밀번호와 토큰은 제외한다. */
    @GetMapping("/{userId}")
    public AdminUserResponse get(@PathVariable Long userId) { return service.get(userId); }

    /** 대상 유저의 변경 이력을 최신순으로 조회한다. */
    @GetMapping("/{userId}/audit-logs")
    public PageResponse<UserAuditResponse> audit(@PathVariable Long userId,
        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.audit(userId, PageRequest.of(page, size, null, null));
    }

    /** 일반 로컬 계정을 생성한다. 관리자 승격은 별도 역할 변경 API를 사용한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserResponse create(@Valid @RequestBody AdminUserCreateRequest request,
        @AuthenticationPrincipal UserPrincipal principal) { return service.create(request, principal.getUserId()); }

    /** 이름과 전화번호만 수정한다. 변경 사유는 필수다. */
    @PatchMapping("/{userId}")
    public AdminUserResponse update(@PathVariable Long userId, @Valid @RequestBody AdminUserUpdateRequest request,
        @AuthenticationPrincipal UserPrincipal principal) { return service.update(userId, request, principal.getUserId()); }

    /** USER/ADMIN 역할을 변경한다. 자기 계정과 마지막 활성 관리자는 보호한다. */
    @PatchMapping("/{userId}/role")
    public AdminUserResponse role(@PathVariable Long userId, @Valid @RequestBody AdminUserRoleRequest request,
        @AuthenticationPrincipal UserPrincipal principal) { return service.changeRole(userId, request, principal.getUserId()); }

    /** 계정을 정지하거나 정지를 해제한다. 탈퇴는 DELETE API로 처리한다. */
    @PatchMapping("/{userId}/status")
    public AdminUserResponse status(@PathVariable Long userId, @Valid @RequestBody AdminUserStatusRequest request,
        @AuthenticationPrincipal UserPrincipal principal) { return service.changeStatus(userId, request, principal.getUserId()); }

    /** 연결 데이터를 보존하며 DELETED 상태로 탈퇴 처리한다. */
    @DeleteMapping("/{userId}")
    public AdminUserResponse delete(@PathVariable Long userId, @Valid @RequestBody AdminUserDeleteRequest request,
        @AuthenticationPrincipal UserPrincipal principal) { return service.delete(userId, request, principal.getUserId()); }
}
