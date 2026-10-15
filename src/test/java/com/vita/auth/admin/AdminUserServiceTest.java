package com.vita.auth.admin;

import com.vita.auth.Role;
import com.vita.auth.UserStatus;
import com.vita.auth.dto.*;
import com.vita.auth.entity.User;
import com.vita.auth.entity.UserAuditLog;
import com.vita.auth.repository.*;
import com.vita.auth.service.AdminUserService;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {
    @Mock UserRepository users;
    @Mock UserAuditLogRepository logs;
    @Mock PasswordEncoder encoder;
    @InjectMocks AdminUserService service;
    User admin;
    User target;

    @BeforeEach void setup() {
        admin = user(9L, Role.ADMIN);
        target = user(1L, Role.USER);
        lenient().when(users.findByIdForUpdate(9L)).thenReturn(Optional.of(admin));
        lenient().when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(target));
    }

    static User user(Long id, Role role) {
        User user = User.builder().email("user" + id + "@example.com").passwordHash("hash").name("Name").role(role).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    @Test void createHashesPasswordAndAlwaysStartsAsUserWithAudit() {
        when(encoder.encode("P@ssw0rd123")).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 2L);
            return user;
        });
        var response = service.create(new AdminUserCreateRequest("new@example.com", "P@ssw0rd123", " New ", null, "계정 발급"), 9L);
        assertThat(response.role()).isEqualTo(Role.USER);
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.name()).isEqualTo("New");
        var userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("encoded");
        var logCaptor = ArgumentCaptor.forClass(UserAuditLog.class);
        verify(logs).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getActorId()).isEqualTo(9L);
        assertThat(logCaptor.getValue().getTargetId()).isEqualTo(2L);
        assertThat(logCaptor.getValue().getAfterState()).isEqualTo("USER/ACTIVE");
    }

    @Test void duplicateEmailAndInvalidPasswordAreRejected() {
        when(users.existsByEmail("taken@example.com")).thenReturn(true);
        assertCode(() -> service.create(new AdminUserCreateRequest("taken@example.com", "P@ssw0rd123", "Name", null, "reason"), 9L), ErrorCode.EMAIL_ALREADY_EXISTS);
        assertCode(() -> service.create(new AdminUserCreateRequest("new@example.com", "weak", "Name", null, "reason"), 9L), ErrorCode.INVALID_PASSWORD_FORMAT);
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(logs);
    }

    @Test void updatesOnlyProfileAndRecordsChangedFields() {
        target.changePhone("010-1234-5678");
        service.update(1L, new AdminUserUpdateRequest("Changed", "", "수정"), 9L);
        assertThat(target.getPhone()).isNull();
        assertThat(target.getName()).isEqualTo("Changed");
        assertThat(target.getTokenVersion()).isZero();
        assertThat(target.getPasswordHash()).isEqualTo("hash");
        var captor = ArgumentCaptor.forClass(UserAuditLog.class);
        verify(logs).save(captor.capture());
        assertThat(captor.getValue().getChangedFields()).isEqualTo("name,phone");
        assertThat(captor.getValue().getBeforeState()).isEqualTo("USER/ACTIVE");
    }

    @Test void statusChangesRevokeOldTokensAndRepeatedRequestsDoNotDuplicateAudit() {
        service.changeStatus(1L, new AdminUserStatusRequest(UserStatus.SUSPENDED, "정지"), 9L);
        service.changeStatus(1L, new AdminUserStatusRequest(UserStatus.SUSPENDED, "정지"), 9L);
        assertThat(target.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(target.getTokenVersion()).isEqualTo(1);
        service.changeStatus(1L, new AdminUserStatusRequest(UserStatus.ACTIVE, "해제"), 9L);
        assertThat(target.getTokenVersion()).isEqualTo(2);
        verify(logs, times(2)).save(any());
    }

    @Test void deletionPreservesRowAndIsIdempotentButCannotBeRestored() {
        service.delete(1L, new AdminUserDeleteRequest("탈퇴 처리"), 9L);
        service.delete(1L, new AdminUserDeleteRequest("탈퇴 처리"), 9L);
        assertThat(target.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(target.getTokenVersion()).isEqualTo(1);
        verify(users, never()).delete(any(User.class));
        verify(logs, times(1)).save(any());
        assertCode(() -> service.update(1L, new AdminUserUpdateRequest("Name", null, "수정"), 9L), ErrorCode.USER_DELETED);
        assertCode(() -> service.changeStatus(1L, new AdminUserStatusRequest(UserStatus.ACTIVE, "복구"), 9L), ErrorCode.USER_DELETED);
        assertCode(() -> service.changeRole(1L, new AdminUserRoleRequest(Role.ADMIN, "승격"), 9L), ErrorCode.USER_DELETED);
    }

    @Test void selfRoleStatusAndDeleteAreProtected() {
        assertCode(() -> service.changeRole(9L, new AdminUserRoleRequest(Role.USER, "강등"), 9L), ErrorCode.SELF_ACCOUNT_PROTECTED);
        assertCode(() -> service.changeStatus(9L, new AdminUserStatusRequest(UserStatus.SUSPENDED, "정지"), 9L), ErrorCode.SELF_ACCOUNT_PROTECTED);
        assertCode(() -> service.delete(9L, new AdminUserDeleteRequest("삭제"), 9L), ErrorCode.SELF_ACCOUNT_PROTECTED);
        verifyNoInteractions(logs);
    }

    @Test void lastActiveAdminIsProtectedForEveryDangerousOperation() {
        target = user(1L, Role.ADMIN);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(target));
        when(users.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).thenReturn(1L);
        assertCode(() -> service.changeRole(1L, new AdminUserRoleRequest(Role.USER, "강등"), 9L), ErrorCode.LAST_ADMIN_PROTECTED);
        assertCode(() -> service.changeStatus(1L, new AdminUserStatusRequest(UserStatus.SUSPENDED, "정지"), 9L), ErrorCode.LAST_ADMIN_PROTECTED);
        assertCode(() -> service.delete(1L, new AdminUserDeleteRequest("삭제"), 9L), ErrorCode.LAST_ADMIN_PROTECTED);
        verifyNoInteractions(logs);
    }

    @Test void roleChangeLocksPolicyBeforeCheckingAndRevokesTokens() {
        target = user(1L, Role.ADMIN);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(target));
        when(users.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        service.changeRole(1L, new AdminUserRoleRequest(Role.USER, "권한 회수"), 9L);
        var ordered = inOrder(users);
        ordered.verify(users).lockManagement();
        ordered.verify(users).findByIdForUpdate(9L);
        ordered.verify(users).findByIdForUpdate(1L);
        ordered.verify(users).countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE);
        assertThat(target.getRole()).isEqualTo(Role.USER);
        assertThat(target.getTokenVersion()).isEqualTo(1);
    }

    @Test void mutationRechecksActorRoleAndStatusInsideTransaction() {
        when(users.findByIdForUpdate(9L)).thenReturn(Optional.of(user(9L, Role.USER)));
        assertCode(() -> service.delete(1L, new AdminUserDeleteRequest("삭제"), 9L), ErrorCode.FORBIDDEN);
        admin.changeStatus(UserStatus.SUSPENDED);
        when(users.findByIdForUpdate(9L)).thenReturn(Optional.of(admin));
        assertCode(() -> service.delete(1L, new AdminUserDeleteRequest("삭제"), 9L), ErrorCode.FORBIDDEN);
        verify(users, never()).findByIdForUpdate(1L);
        verifyNoInteractions(logs);
    }

    @Test void pageBoundsUnknownSortAndEmptyUpdateAreBadRequests() {
        for (var request : new PageRequest[]{PageRequest.of(-1, 20, null, null), PageRequest.of(0, 0, null, null),
            PageRequest.of(0, 101, null, null), PageRequest.of(0, 20, null, "passwordHash,asc"),
            PageRequest.of(0, 20, null, "name,sideways")}) {
            assertCode(() -> service.list(request, null, null), ErrorCode.VALIDATION_ERROR);
        }
        assertCode(() -> service.update(1L, new AdminUserUpdateRequest(null, null, "수정"), 9L), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.changeStatus(1L, new AdminUserStatusRequest(UserStatus.DELETED, "삭제"), 9L), ErrorCode.VALIDATION_ERROR);
    }

    @Test void missingTargetReturns404() {
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertCode(() -> service.delete(1L, new AdminUserDeleteRequest("삭제"), 9L), ErrorCode.USER_NOT_FOUND);
    }
}
