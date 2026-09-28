package com.vita.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.vita.auth.Role;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 회원 토큰과 게스트 헤더가 각각 어떤 주체로 해석되는지 검증한다.
 *
 * <p>인증은 조용히 뚫려도 동작은 멀쩡해 보이는 영역이라, 통과 여부뿐 아니라
 * 권한까지 확인한다.
 */
class JwtAuthenticationFilterTest {

	private static final String SECRET = "test-secret-key-for-jwt-at-least-32-bytes-long";
	private static final long EXPIRE_MILLIS = 60_000L;

	private final JwtProvider jwtProvider = new JwtProvider(SECRET, EXPIRE_MILLIS);
	private final CookieUtil cookieUtil = new CookieUtil(false, "Lax");
	private final JwtAuthenticationFilter filter =
			new JwtAuthenticationFilter(jwtProvider, cookieUtil);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private Authentication authenticate(MockHttpServletRequest request) throws Exception {
		FilterChain chain = new MockFilterChain();
		filter.doFilter(request, new MockHttpServletResponse(), chain);
		return SecurityContextHolder.getContext().getAuthentication();
	}

	@Test
	@DisplayName("유효한 토큰이면 회원으로 인증되고 guestId는 비어 있다")
	void memberToken() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + jwtProvider.createAccessToken(1L, Role.USER));

		UserPrincipal principal = (UserPrincipal) authenticate(request).getPrincipal();

		assertThat(principal.getUserId()).isEqualTo(1L);
		assertThat(principal.getGuestId()).isNull();
		assertThat(principal.isGuest()).isFalse();
		assertThat(principal.getAuthorities())
				.extracting("authority").containsExactly("ROLE_USER");
	}

	@Test
	@DisplayName("토큰 없이 X-Guest-Id만 오면 게스트로 인증된다")
	void guestHeader() throws Exception {
		UUID guestId = UUID.randomUUID();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Guest-Id", guestId.toString());

		UserPrincipal principal = (UserPrincipal) authenticate(request).getPrincipal();

		assertThat(principal.getGuestId()).isEqualTo(guestId);
		assertThat(principal.getUserId()).isNull();
		assertThat(principal.isGuest()).isTrue();
	}

	@Test
	@DisplayName("게스트에게는 ROLE_GUEST만 준다 — ROLE_USER를 주면 회원 API가 헤더 하나로 열린다")
	void guestHasOnlyGuestRole() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Guest-Id", UUID.randomUUID().toString());

		UserPrincipal principal = (UserPrincipal) authenticate(request).getPrincipal();

		assertThat(principal.getAuthorities())
				.extracting("authority").containsExactly("ROLE_GUEST");
	}

	@Test
	@DisplayName("로그인 상태에서 게스트 헤더가 함께 와도 회원으로 본다")
	void memberWinsOverGuestHeader() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + jwtProvider.createAccessToken(7L, Role.USER));
		request.addHeader("X-Guest-Id", UUID.randomUUID().toString());

		UserPrincipal principal = (UserPrincipal) authenticate(request).getPrincipal();

		assertThat(principal.getUserId()).isEqualTo(7L);
		assertThat(principal.getGuestId()).isNull();
	}

	@Test
	@DisplayName("UUID 형식이 아닌 게스트 헤더는 인정하지 않는다")
	void malformedGuestId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Guest-Id", "not-a-uuid");

		assertThat(authenticate(request)).isNull();
	}

	@Test
	@DisplayName("토큰도 게스트 헤더도 없으면 인증되지 않는다 — 차단은 인가 단계가 한다")
	void anonymous() throws Exception {
		assertThat(authenticate(new MockHttpServletRequest())).isNull();
	}

	@Test
	@DisplayName("위조된 토큰은 게스트 헤더가 있으면 게스트로 떨어진다")
	void forgedTokenFallsBackToGuest() throws Exception {
		UUID guestId = UUID.randomUUID();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer forged.token.value");
		request.addHeader("X-Guest-Id", guestId.toString());

		UserPrincipal principal = (UserPrincipal) authenticate(request).getPrincipal();

		assertThat(principal.isGuest()).isTrue();
		assertThat(principal.getGuestId()).isEqualTo(guestId);
	}
}
