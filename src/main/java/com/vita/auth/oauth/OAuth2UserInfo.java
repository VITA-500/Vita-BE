package com.vita.auth.oauth;

import com.vita.auth.AuthProvider;

/**
 * 소셜 제공자가 내려주는 사용자 정보를 동일한 모양으로 다루기 위한 추상화.
 *
 * <p>구글·카카오·네이버는 같은 정보를 전혀 다른 JSON 구조로 준다. 이 차이를 구현체 안에
 * 가둬두면 상위 로직(CustomOAuth2UserService)은 제공자를 구분할 필요가 없다.
 */
public interface OAuth2UserInfo {

	/** 이 정보를 내려준 제공자. user_oauth.provider에 그대로 저장된다. */
	AuthProvider getProvider();

	/**
	 * 제공자 내에서 사용자를 유일하게 식별하는 값. 이메일과 달리 바뀌지 않고, 동의 항목이
	 * 아니라서 항상 내려온다. 로그인 식별은 (provider, providerId) 조합으로 한다.
	 */
	String getProviderId();

	/** 카카오는 비즈앱 전환 전까지 이메일을 주지 않으므로 null일 수 있다. */
	String getEmail();

	/** 표시용 이름. 제공자에 따라 없을 수도 있다. */
	String getName();
}
