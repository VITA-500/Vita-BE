package com.vita.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 어떤 요청이 CSRF 검사를 받고 어떤 요청이 면제되는지 검증한다.
 *
 * <p>CSRF는 브라우저가 자격증명(쿠키)을 자동으로 실어 보내기 때문에 성립한다.
 * 쿠키로 인증하는 요청만 검사하면 되고, 헤더로 인증하는 요청은 공격자가 값을
 * 직접 채워야 하므로 면제해도 방어가 약해지지 않는다.
 *
 * <p>면제 범위를 넓히면 조용히 CSRF가 뚫리므로, "쿠키가 있으면 반드시 검사한다"를
 * 테스트로 고정한다.
 */
class CsrfExemptionTest {

	private final SecurityConfig config =
			new SecurityConfig(null, null, new CookieUtil(false, "Lax"), null, null, null, null);

	@Test
	@DisplayName("게스트 요청은 쿠키가 없어 CSRF 검사를 면제받는다 — 403의 원인이던 지점")
	void guestRequestIsExempt() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat/sessions");
		request.addHeader("X-Guest-Id", UUID.randomUUID().toString());

		assertThat(config.isNotCookieAuthenticated(request)).isTrue();
	}

	@Test
	@DisplayName("같은 경로라도 쿠키로 인증한 회원이면 CSRF 검사를 받는다")
	void cookieAuthenticatedMemberIsChecked() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat/sessions");
		request.setCookies(new Cookie(CookieUtil.ACCESS_TOKEN, "token-value"));

		assertThat(config.isNotCookieAuthenticated(request)).isFalse();
	}

	@Test
	@DisplayName("Authorization 헤더로 인증하면 면제된다 — Swagger·curl이 막히지 않는다")
	void headerAuthenticatedRequestIsExempt() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat/sessions");
		request.addHeader("Authorization", "Bearer token-value");

		assertThat(config.isNotCookieAuthenticated(request)).isTrue();
	}

	@Test
	@DisplayName("빈 쿠키는 인증으로 보지 않는다 — 로그아웃 직후 만료 쿠키가 남는 경우")
	void blankCookieIsNotAuthentication() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat/sessions");
		request.setCookies(new Cookie(CookieUtil.ACCESS_TOKEN, ""));

		assertThat(config.isNotCookieAuthenticated(request)).isTrue();
	}

	@Test
	@DisplayName("쿠키와 게스트 헤더가 함께 오면 검사 대상이다 — 헤더를 붙여 면제를 얻을 수 없다")
	void guestHeaderCannotBypassCookieCheck() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat/sessions");
		request.setCookies(new Cookie(CookieUtil.ACCESS_TOKEN, "token-value"));
		request.addHeader("X-Guest-Id", UUID.randomUUID().toString());

		assertThat(config.isNotCookieAuthenticated(request)).isFalse();
	}
}
