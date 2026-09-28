package com.vita.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;

/**
 * XSRF-TOKEN 쿠키가 인증 쿠키와 같은 Secure/SameSite로 내려가는지 검증한다.
 *
 * <p>배포 환경은 프론트와 백엔드 도메인이 달라 SameSite=None이어야 쿠키가 실린다. CSRF 쿠키만
 * 기본값(SameSite 없음 = Lax)으로 나가면 cross-site PATCH/POST에서 이 쿠키가 빠져 403이 난다.
 *
 * <p>Set-Cookie 헤더 문자열이 아니라 Cookie 객체를 검사한다. Spring은 SameSite를 Cookie의
 * attribute로 넣고 Tomcat이 이를 헤더로 쓰는데, MockHttpServletResponse는 헤더를 만들 때
 * 이 attribute를 빠뜨린다.
 */
class CsrfCookieAttributesTest {

	@Test
	@DisplayName("배포 설정(None, Secure)이면 CSRF 쿠키도 SameSite=None; Secure로 나간다")
	void crossSiteAttributesAreApplied() {
		Cookie cookie = issueCsrfCookie(new CookieUtil(true, "None"));

		assertThat(cookie.getAttribute("SameSite")).isEqualTo("None");
		assertThat(cookie.getSecure()).isTrue();
	}

	@Test
	@DisplayName("로컬 설정(Lax)이면 CSRF 쿠키도 SameSite=Lax로 나간다")
	void localAttributesAreApplied() {
		Cookie cookie = issueCsrfCookie(new CookieUtil(false, "Lax"));

		assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
		assertThat(cookie.getSecure()).isFalse();
	}

	@Test
	@DisplayName("프론트가 읽을 수 있도록 HttpOnly는 붙지 않는다")
	void csrfCookieIsReadableByScript() {
		Cookie cookie = issueCsrfCookie(new CookieUtil(true, "None"));

		assertThat(cookie.isHttpOnly()).isFalse();
	}

	private Cookie issueCsrfCookie(CookieUtil cookieUtil) {
		SecurityConfig config = new SecurityConfig(null, null, cookieUtil, null, null, null, null);
		CookieCsrfTokenRepository repository = config.csrfTokenRepository();

		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		CsrfToken token = repository.generateToken(request);
		repository.saveToken(token, request, response);

		return response.getCookie("XSRF-TOKEN");
	}
}
