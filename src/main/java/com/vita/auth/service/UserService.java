package com.vita.auth.service;

import com.vita.auth.dto.MyPageResponse;
import com.vita.auth.entity.AuthProvider;
import com.vita.auth.entity.User;
import com.vita.auth.entity.UserOAuth;
import com.vita.auth.repository.UserOAuthRepository;
import com.vita.auth.repository.UserRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;
	private final UserOAuthRepository userOAuthRepository;

	@Transactional(readOnly = true)
	public MyPageResponse getMyPage(Long userId) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

		List<AuthProvider> providers = userOAuthRepository.findAllByUserId(userId).stream()
				.map(UserOAuth::getProvider)
				.toList();

		return MyPageResponse.of(user, providers);
	}
}
