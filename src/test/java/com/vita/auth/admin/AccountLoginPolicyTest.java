package com.vita.auth.admin;

import com.vita.auth.UserStatus;
import com.vita.auth.dto.LoginRequest;
import com.vita.auth.entity.User;
import com.vita.auth.oauth.CustomOAuth2UserService;
import com.vita.auth.oauth.OAuth2SuccessHandler;
import com.vita.auth.repository.UserRepository;
import com.vita.auth.security.CookieUtil;
import com.vita.auth.security.JwtProvider;
import com.vita.auth.service.AuthService;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountLoginPolicyTest {
    @Test void suspendedAndDeletedLocalAccountsCannotLogin() {
        var users = mock(UserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var jwt = mock(JwtProvider.class);
        var service = new AuthService(users, encoder, jwt);
        for (UserStatus status : new UserStatus[]{UserStatus.SUSPENDED, UserStatus.DELETED}) {
            User user = User.ofLocal("user@example.com", "hash", "Name");
            user.changeStatus(status);
            when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "P@ssw0rd123")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        }
        verifyNoInteractions(encoder, jwt);
    }

    @Test void restoredLocalAccountReceivesCurrentTokenVersion() {
        var users = mock(UserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var jwt = mock(JwtProvider.class);
        User user = AdminUserServiceTest.user(1L, com.vita.auth.Role.USER);
        user.changeStatus(UserStatus.SUSPENDED);
        user.changeStatus(UserStatus.ACTIVE);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(encoder.matches("P@ssw0rd123", "hash")).thenReturn(true);
        when(jwt.createAccessToken(1L, user.getRole(), 2L)).thenReturn("current-token");
        var response = new AuthService(users, encoder, jwt).login(new LoginRequest(user.getEmail(), "P@ssw0rd123"));
        assertThat(response.accessToken()).isEqualTo("current-token");
    }

    @Test void socialSuccessIssuesCurrentTokenVersion() throws Exception {
        var jwt = new JwtProvider("test-secret-key-for-jwt-at-least-32-bytes-long", 60_000);
        var handler = new OAuth2SuccessHandler(jwt, new CookieUtil(false, "Lax"));
        ReflectionTestUtils.setField(handler, "redirectUri", "http://localhost/callback");
        User user = User.ofSocial(null, "Social");
        ReflectionTestUtils.setField(user, "id", 1L);
        user.changeStatus(UserStatus.SUSPENDED);
        user.changeStatus(UserStatus.ACTIVE);
        var adapter = new CustomOAuth2UserService.OAuth2UserAdapter(user, java.util.Map.of());
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
            new UsernamePasswordAuthenticationToken(adapter, null, adapter.getAuthorities()));
        var cookie = response.getCookie(CookieUtil.ACCESS_TOKEN);
        assertThat(cookie).isNotNull();
        assertThat(jwt.getTokenVersion(jwt.parseOrNull(cookie.getValue()))).isEqualTo(2);
    }
}
