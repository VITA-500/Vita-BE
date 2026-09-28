package com.vita.auth.oauth;

import com.vita.auth.AuthProvider;
import java.util.Map;

/**
 * 구글 응답 — 중첩 없이 평평하다.
 * <pre>{ "sub": "1092837...", "email": "a@gmail.com", "name": "홍길동" }</pre>
 */
public class GoogleUserInfo implements OAuth2UserInfo {

	private final Map<String, Object> attributes;

	public GoogleUserInfo(Map<String, Object> attributes) {
		this.attributes = attributes;
	}

	@Override
	public AuthProvider getProvider() {
		return AuthProvider.GOOGLE;
	}

	@Override
	public String getProviderId() {
		return asString(attributes.get("sub"));
	}

	@Override
	public String getEmail() {
		return asString(attributes.get("email"));
	}

	@Override
	public String getName() {
		return asString(attributes.get("name"));
	}

	/** 값이 없으면 "null" 문자열이 되지 않도록 null을 그대로 돌려준다. */
	private String asString(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
