package com.vita.auth.oauth;

import com.vita.auth.AuthProvider;
import java.util.Map;

/**
 * 카카오 응답 — 두 겹으로 중첩되어 있다.
 * <pre>
 * {
 *   "id": 3821994,
 *   "kakao_account": {
 *     "email": "a@kakao.com",        // 동의 안 하면 키 자체가 없다
 *     "profile": { "nickname": "길동" }
 *   }
 * }
 * </pre>
 *
 * <p>id는 숫자로 오지만 구글·네이버는 문자열이라 String으로 통일한다.
 * 중첩 단계마다 null 검사가 필요하다 — 사용자가 동의를 거부하면 그 키가 아예 없다.
 */
public class KakaoUserInfo implements OAuth2UserInfo {

	private final Map<String, Object> attributes;

	public KakaoUserInfo(Map<String, Object> attributes) {
		this.attributes = attributes;
	}

	@Override
	public AuthProvider getProvider() {
		return AuthProvider.KAKAO;
	}

	@Override
	public String getProviderId() {
		Object id = attributes.get("id");
		return id == null ? null : String.valueOf(id);
	}

	@Override
	public String getEmail() {
		Map<String, Object> account = nested(attributes, "kakao_account");
		return account == null ? null : asString(account.get("email"));
	}

	@Override
	public String getName() {
		Map<String, Object> account = nested(attributes, "kakao_account");
		if (account == null) {
			return null;
		}
		Map<String, Object> profile = nested(account, "profile");
		return profile == null ? null : asString(profile.get("nickname"));
	}

	/** 중첩 Map을 한 겹 꺼낸다. 키가 없거나 Map이 아니면 null — 동의를 거부하면 키 자체가 없다. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> nested(Map<String, Object> source, String key) {
		Object value = source.get(key);
		return value instanceof Map ? (Map<String, Object>) value : null;
	}

	/** 값이 없으면 "null" 문자열이 되지 않도록 null을 그대로 돌려준다. */
	private String asString(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
