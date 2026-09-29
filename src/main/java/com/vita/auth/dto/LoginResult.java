package com.vita.auth.dto;

import com.vita.auth.entity.User;
import com.vita.auth.security.TokenIssuer.IssuedTokens;

/**
 * 서비스 → 컨트롤러 내부 전달용. 응답 body(LoginResponse)와 쿠키에 담을 토큰을 함께 넘긴다.
 *
 * <p>토큰을 쿠키로 내보내는 것은 HTTP 응답을 다루는 일이라 컨트롤러의 몫이고, 서비스는
 * 토큰을 만들기만 한다. 이 record가 그 경계를 지킨다.
 */
public record LoginResult(IssuedTokens tokens, LoginResponse response) {

	public static LoginResult of(IssuedTokens tokens, User user) {
		return new LoginResult(tokens, LoginResponse.from(user));
	}
}
