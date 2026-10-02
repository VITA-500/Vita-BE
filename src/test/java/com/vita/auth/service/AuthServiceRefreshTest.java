package com.vita.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vita.auth.Role;
import com.vita.auth.entity.User;
import com.vita.auth.repository.UserRepository;
import com.vita.auth.security.RefreshTokenStore;
import com.vita.auth.security.TokenIssuer;
import com.vita.auth.security.TokenIssuer.IssuedTokens;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceRefreshTest {

	private UserRepository userRepository;
	private TokenIssuer tokenIssuer;
	private RefreshTokenStore refreshTokenStore;
	private AuthService authService;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		tokenIssuer = mock(TokenIssuer.class);
		refreshTokenStore = mock(RefreshTokenStore.class);
		authService = new AuthService(userRepository, mock(PasswordEncoder.class), tokenIssuer, refreshTokenStore);
	}

	@Test
	@DisplayName("유효한 refreshToken이면 쓴 토큰을 소모하고 DB의 권한으로 새 토큰 쌍을 발급한다")
	void rotatesWithCurrentRole() {
		User user = mock(User.class);
		when(user.getId()).thenReturn(7L);
		when(user.getRole()).thenReturn(Role.ADMIN);
		when(refreshTokenStore.consume("old")).thenReturn(Optional.of(7L));
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		IssuedTokens issued = new IssuedTokens("new-access", "new-refresh");
		when(tokenIssuer.issue(7L, Role.ADMIN)).thenReturn(issued);

		assertThat(authService.refresh("old")).isEqualTo(issued);
		verify(refreshTokenStore).consume("old");
	}

	@Test
	@DisplayName("없거나 이미 쓰인 refreshToken은 INVALID_TOKEN이다")
	void rejectsUnknownOrReusedToken() {
		when(refreshTokenStore.consume("used")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.refresh("used"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_TOKEN);
		verify(tokenIssuer, never()).issue(any(), any());
	}

	@Test
	@DisplayName("토큰의 사용자가 탈퇴해 없으면 INVALID_TOKEN이다")
	void rejectsWhenUserGone() {
		when(refreshTokenStore.consume("orphan")).thenReturn(Optional.of(99L));
		when(userRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.refresh("orphan"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_TOKEN);
		verify(tokenIssuer, never()).issue(any(), any());
	}

	@Test
	@DisplayName("로그아웃하면 refreshToken을 폐기한다")
	void logoutRevokes() {
		authService.logout("token");

		verify(refreshTokenStore).revoke("token");
	}
}
