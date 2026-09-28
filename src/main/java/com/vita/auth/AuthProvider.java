package com.vita.auth;

/** user_oauth.provider — DB의 auth_provider ENUM과 값이 일치해야 한다. LOCAL은 자체 가입. */
public enum AuthProvider {
	LOCAL,
	GOOGLE,
	KAKAO,
	NAVER
}
