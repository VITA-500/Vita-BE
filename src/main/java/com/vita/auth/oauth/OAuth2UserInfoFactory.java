package com.vita.auth.oauth;

import com.vita.auth.AuthProvider;
import java.util.Map;

/** registrationId(google/kakao/naver)에 맞는 구현체를 고른다. */
public final class OAuth2UserInfoFactory {

	private OAuth2UserInfoFactory() {
	}

	/**
	 * registrationId에 맞는 구현체에 attributes를 싸서 돌려준다.
	 *
	 * @throws IllegalArgumentException 지원하지 않는 제공자이거나 LOCAL인 경우
	 */
	public static OAuth2UserInfo of(String registrationId, Map<String, Object> attributes) {
		AuthProvider provider = toProvider(registrationId);
		return switch (provider) {
			case GOOGLE -> new GoogleUserInfo(attributes);
			case KAKAO -> new KakaoUserInfo(attributes);
			case NAVER -> new NaverUserInfo(attributes);
			case LOCAL -> throw new IllegalArgumentException("LOCAL은 소셜 제공자가 아닙니다.");
		};
	}

	/** application.yml의 registration 키(google/kakao/naver)를 enum으로 바꾼다. */
	private static AuthProvider toProvider(String registrationId) {
		try {
			return AuthProvider.valueOf(registrationId.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("지원하지 않는 소셜 제공자: " + registrationId, e);
		}
	}
}
