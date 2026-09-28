package com.vita.auth.oauth;

import com.vita.auth.oauth.CustomOAuth2UserService.OAuth2UserAdapter;
import com.vita.auth.entity.User;
import com.vita.auth.security.CookieUtil;
import com.vita.auth.security.JwtProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 소셜 인증이 끝나면 우리 JWT를 발급해 프론트로 리다이렉트한다.
 *
 * <p>여기서 발급하는 토큰은 자체 로그인과 완전히 동일하다 — 소셜 제공자의 토큰은 사용자 정보를
 * 받아오는 데만 쓰고 버린다. 그래서 이후 모든 API는 로그인 경로를 구분할 필요가 없다.
 *
 * <p>토큰은 HttpOnly 쿠키로 내려보낸다(FE1 협의, 2026-09-17). 쿼리 파라미터로 넘기면 토큰이
 * 주소창·브라우저 기록·서버 접속 로그에 남지만, 쿠키는 리다이렉트 응답에 함께 실려 그런 노출이 없다.
 */
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

	private final JwtProvider jwtProvider;
	private final CookieUtil cookieUtil;

	@Value("${app.oauth2.redirect-uri}")
	private String redirectUri;

	/** 우리 JWT를 발급해 HttpOnly 쿠키에 담고, 프론트 콜백 주소로 리다이렉트한다. */
	@Override
	public void onAuthenticationSuccess(
			HttpServletRequest request,
			HttpServletResponse response,
			Authentication authentication) throws IOException {

		User user = ((OAuth2UserAdapter) authentication.getPrincipal()).user();
		String accessToken = jwtProvider.createAccessToken(user.getId(), user.getRole());

		ResponseCookie cookie = cookieUtil.create(
				accessToken, Duration.ofMillis(jwtProvider.getAccessTokenExpireMillis()));
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

		getRedirectStrategy().sendRedirect(request, response, redirectUri);
	}
}
