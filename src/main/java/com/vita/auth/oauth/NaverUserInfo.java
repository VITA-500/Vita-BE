package com.vita.auth.oauth;

import com.vita.auth.AuthProvider;
import java.util.Map;

/**
 * 네이버 응답 — 실제 값이 response 안에 한 겹 들어 있다.
 * <pre>
 * {
 *   "resultcode": "00",
 *   "response": { "id": "32189...", "email": "a@naver.com", "name": "홍길동" }
 * }
 * </pre>
 *
 * <p>application.yml의 user-name-attribute를 response로 지정해두면 Spring이 이 안쪽 Map을
 * 넘겨주지만, 설정이 어긋나 바깥 Map이 올 수도 있어 양쪽 모두 처리한다.
 */
public class NaverUserInfo implements OAuth2UserInfo {

	private final Map<String, Object> response;

	/** response 키가 있으면 그 안쪽을, 없으면 받은 Map을 그대로 쓴다(설정 어긋남 대비). */
	@SuppressWarnings("unchecked")
	public NaverUserInfo(Map<String, Object> attributes) {
		Object nested = attributes.get("response");
		this.response = nested instanceof Map ? (Map<String, Object>) nested : attributes;
	}

	@Override
	public AuthProvider getProvider() {
		return AuthProvider.NAVER;
	}

	@Override
	public String getProviderId() {
		return asString(response.get("id"));
	}

	@Override
	public String getEmail() {
		return asString(response.get("email"));
	}

	@Override
	public String getName() {
		return asString(response.get("name"));
	}

	/** 값이 없으면 "null" 문자열이 되지 않도록 null을 그대로 돌려준다. */
	private String asString(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
