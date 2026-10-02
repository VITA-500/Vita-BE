package com.vita.auth.security;

import com.vita.auth.Role;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * accessToken·refreshToken을 한 쌍으로 발급하고 쿠키로 만든다.
 *
 * <p>자체 로그인, 소셜 로그인, 토큰 재발급이 모두 같은 일을 한다. 한 곳에 모아 두지 않으면
 * 어느 한쪽만 refreshToken을 빠뜨리거나 쿠키 수명이 달라지는 일이 생긴다.
 */
@Component
@RequiredArgsConstructor
public class TokenIssuer {

	private final JwtProvider jwtProvider;
	private final RefreshTokenStore refreshTokenStore;
	private final CookieUtil cookieUtil;

	public IssuedTokens issue(Long userId, Role role) {
		return new IssuedTokens(
				jwtProvider.createAccessToken(userId, role),
				refreshTokenStore.issue(userId));
	}

	/** 쿠키 수명은 토큰 수명과 같게 둔다 — 쿠키만 남아 만료된 토큰을 계속 보내는 일이 없도록. */
	public List<ResponseCookie> toCookies(IssuedTokens tokens) {
		return List.of(
				cookieUtil.create(tokens.accessToken(),
						Duration.ofMillis(jwtProvider.getAccessTokenExpireMillis())),
				cookieUtil.createRefresh(tokens.refreshToken(), refreshTokenStore.getTtl()));
	}

	public record IssuedTokens(String accessToken, String refreshToken) {
	}
}
