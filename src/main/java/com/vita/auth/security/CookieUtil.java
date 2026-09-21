package com.vita.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Access Token을 담는 쿠키를 만들고 읽는다.
 *
 * <p>HttpOnly로 내려보내 자바스크립트가 토큰을 읽지 못하게 한다 — XSS로 스크립트가 삽입돼도
 * 토큰을 훔쳐갈 수 없다. 대신 브라우저가 요청마다 자동으로 실어 보내므로 CSRF 방어가 필요해지고,
 * 그건 SecurityConfig에서 CSRF 토큰으로 막는다.
 *
 * <p>배포 환경은 프론트(Vercel)와 백엔드(EC2) 도메인이 달라 SameSite=None이 필요하고,
 * 그 경우 브라우저가 Secure를 요구하므로 HTTPS에서만 동작한다. 로컬은 http라 Lax로 둔다.
 */
@Component
public class CookieUtil {

	public static final String ACCESS_TOKEN = "accessToken";

	/** 톰캣이 발급하는 세션 쿠키 이름. 우리 인증은 세션을 쓰지 않지만 OAuth2의 state 검증이 쓴다. */
	private static final String SESSION_COOKIE = "JSESSIONID";

	private final boolean secure;
	private final String sameSite;

	public CookieUtil(
			@Value("${app.cookie.secure:false}") boolean secure,
			@Value("${app.cookie.same-site:Lax}") String sameSite) {
		this.secure = secure;
		this.sameSite = sameSite;
	}

	public ResponseCookie create(String token, Duration maxAge) {
		return ResponseCookie.from(ACCESS_TOKEN, token)
				.httpOnly(true)
				.secure(secure)
				.sameSite(sameSite)
				.path("/")
				.maxAge(maxAge)
				.build();
	}

	/** 로그아웃용 — 같은 속성으로 만료시켜야 브라우저가 실제로 지운다. */
	public ResponseCookie expire() {
		return ResponseCookie.from(ACCESS_TOKEN, "")
				.httpOnly(true)
				.secure(secure)
				.sameSite(sameSite)
				.path("/")
				.maxAge(0)
				.build();
	}

	/**
	 * 로그아웃용 세션 쿠키 만료. 서버에서 session.invalidate()를 해도 브라우저는 죽은
	 * JSESSIONID를 계속 보내고, 서버는 그 값으로 빈 세션을 다시 만든다. 쿠키까지 지워야
	 * 다음 소셜 로그인이 깨끗한 세션에서 시작한다.
	 */
	public ResponseCookie expireSession() {
		return ResponseCookie.from(SESSION_COOKIE, "")
				.httpOnly(true)
				.secure(secure)
				.sameSite(sameSite)
				.path("/")
				.maxAge(0)
				.build();
	}

	public Optional<String> read(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		return Arrays.stream(cookies)
				.filter(cookie -> ACCESS_TOKEN.equals(cookie.getName()))
				.map(Cookie::getValue)
				.filter(value -> !value.isBlank())
				.findFirst();
	}
}
