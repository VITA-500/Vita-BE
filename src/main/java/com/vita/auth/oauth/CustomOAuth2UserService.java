package com.vita.auth.oauth;

import com.vita.auth.OAuth2AuthenticationProcessingException;
import com.vita.auth.entity.User;
import com.vita.auth.entity.UserOAuth;
import com.vita.auth.repository.UserOAuthRepository;
import com.vita.auth.repository.UserRepository;
import com.vita.common.exception.ErrorCode;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 제공자에서 받은 정보로 회원을 찾거나 새로 만든다.
 *
 * <p>토큰 교환과 사용자 정보 조회는 부모(DefaultOAuth2UserService)가 이미 끝낸 상태로 들어온다.
 * 여기서 하는 일은 그 정보를 우리 users/user_oauth 테이블과 연결하는 것뿐이다.
 */
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

	private final UserRepository userRepository;
	private final UserOAuthRepository userOAuthRepository;

	/**
	 * 제공자 응답을 우리 회원으로 바꿔 인증 주체를 만든다.
	 *
	 * <p>@Transactional인 이유: 신규 가입이면 users와 user_oauth 두 건을 함께 저장하는데,
	 * 한쪽만 남으면 연결이 끊긴 계정이 생겨 다음 로그인부터 계속 실패한다.
	 */
	@Override
	@Transactional
	public OAuth2User loadUser(OAuth2UserRequest userRequest) {
		OAuth2User oAuth2User = super.loadUser(userRequest);

		String registrationId = userRequest.getClientRegistration().getRegistrationId();
		OAuth2UserInfo userInfo = OAuth2UserInfoFactory.of(registrationId, oAuth2User.getAttributes());

		if (userInfo.getProviderId() == null) {
			// 제공자가 고유 ID를 안 줬다면 로그인 식별 자체가 불가능하다.
			throw new OAuth2AuthenticationProcessingException(ErrorCode.OAUTH_AUTHENTICATION_FAILED);
		}

		User user = findOrCreate(userInfo);
		return new OAuth2UserAdapter(user, oAuth2User.getAttributes());
	}

	/**
	 * 연결된 회원을 찾고, 없으면 새로 가입시킨다.
	 *
	 * <p>로그인 식별은 이메일이 아니라 (provider, providerId)로 한다 — 이메일은 제공자가 주지 않을
	 * 수도 있고 사용자가 바꿀 수도 있어 식별자로 쓸 수 없다.
	 *
	 * @throws OAuth2AuthenticationProcessingException 같은 이메일로 이미 가입된 계정이 있는 경우
	 */
	private User findOrCreate(OAuth2UserInfo userInfo) {
		Optional<UserOAuth> linked = userOAuthRepository
				.findByProviderAndProviderId(userInfo.getProvider(), userInfo.getProviderId());

		if (linked.isPresent()) {
			// findByProviderAndProviderId가 user를 fetch join으로 함께 가져온다 —
			// 여기서 나가는 User는 트랜잭션 밖(SuccessHandler)에서도 안전하게 읽을 수 있다.
			return linked.get().getUser();
		}

		String email = userInfo.getEmail();

		// 같은 이메일로 이미 가입된 계정이 있으면 자동으로 연결하지 않고 거부한다.
		// 소셜 제공자가 그 이메일을 검증했는지 알 수 없어, 자동 연결을 허용하면 공격자가
		// 피해자 이메일로 소셜 계정을 만들어 기존 계정을 가져갈 수 있다.
		if (email != null && userRepository.existsByEmail(email)) {
			throw new OAuth2AuthenticationProcessingException(ErrorCode.OAUTH_EMAIL_CONFLICT);
		}

		User created = userRepository.save(User.ofSocial(email, userInfo.getName()));
		userOAuthRepository.save(
				UserOAuth.of(created, userInfo.getProvider(), userInfo.getProviderId()));
		return created;
	}

	/** SuccessHandler가 userId를 꺼낼 수 있도록 우리 User를 실어 나르는 래퍼. */
	public record OAuth2UserAdapter(User user, Map<String, Object> attributes) implements OAuth2User {

		/** 제공자가 준 원본 응답. 우리 로직은 쓰지 않지만 OAuth2User 규약상 노출해야 한다. */
		@Override
		public Map<String, Object> getAttributes() {
			return attributes;
		}

		/** SecurityConfig의 hasRole("ADMIN")과 맞물리도록 ROLE_ 접두사를 붙인다. */
		@Override
		public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() {
			return java.util.List.of(
					new org.springframework.security.core.authority.SimpleGrantedAuthority(
							"ROLE_" + user.getRole().name()));
		}

		/**
		 * 표시용 이름이 아니라 users.id를 돌려준다 — OAuth2User의 getName()은 "인증 주체의
		 * 식별자"라는 뜻이고, 우리 식별자는 userId다. 소셜 닉네임은 user.name에 따로 있다.
		 */
		@Override
		public String getName() {
			return String.valueOf(user.getId());
		}
	}
}
