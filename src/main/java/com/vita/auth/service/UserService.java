package com.vita.auth.service;

import com.vita.auth.dto.MyPageResponse;
import com.vita.auth.dto.MyPageUpdateRequest;
import com.vita.auth.dto.MyPageUpdateResponse;
import com.vita.auth.AuthProvider;
import com.vita.auth.entity.User;
import com.vita.auth.entity.UserOAuth;
import com.vita.auth.repository.UserOAuthRepository;
import com.vita.auth.repository.UserRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;
	private final UserOAuthRepository userOAuthRepository;
	private final PasswordEncoder passwordEncoder;

	@Transactional(readOnly = true)
	public MyPageResponse getMyPage(Long userId) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

		List<AuthProvider> providers = userOAuthRepository.findAllByUserId(userId).stream()
				.map(UserOAuth::getProvider)
				.toList();

		return MyPageResponse.of(user, providers);
	}

	/**
	 * 내 정보 수정 (04_API명세서 2.2). PATCH라 넘어온 필드만 바꾼다.
	 *
	 * <p>userId는 요청이 아니라 토큰에서 온 값만 받는다 — 요청으로 받으면 남의 id를 넣어
	 * 아무 계정이나 수정할 수 있다(getMyPage와 같은 이유).
	 */
	@Transactional
	public MyPageUpdateResponse updateMyPage(Long userId, MyPageUpdateRequest request) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

		if (request.name() != null) {
			user.changeName(request.name());
		}

		if (request.password() != null) {
			// 소셜 전용 계정은 비밀번호가 없다. 여기서 막지 않으면 소셜 계정에 비밀번호가 생겨
			// 자체 로그인이 가능해진다 — 계정 연결 정책(OAUTH_EMAIL_CONFLICT)을 우회하는 셈이다.
			if (!user.hasPassword()) {
				throw new BusinessException(ErrorCode.PASSWORD_NOT_SUPPORTED);
			}
			user.changePassword(passwordEncoder.encode(request.password()));
		}

		// flush를 직접 부르는 이유 — updatedAt은 Auditing이 flush 시점에 채운다. 그냥 두면
		// 트랜잭션 커밋(=응답 생성 이후)에 flush돼서 응답에 수정 전 시각이 실려 나간다.
		userRepository.flush();

		return MyPageUpdateResponse.from(user);
	}
}
