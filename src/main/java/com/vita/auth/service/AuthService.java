package com.vita.auth.service;

import com.vita.auth.dto.LoginRequest;
import com.vita.auth.dto.LoginResponse;
import com.vita.auth.dto.SignupRequest;
import com.vita.auth.entity.User;
import com.vita.auth.repository.UserRepository;
import com.vita.auth.security.JwtProvider;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtProvider jwtProvider;

	@Transactional
	public void signup(SignupRequest request) {
		if (userRepository.existsByEmail(request.email())) {
			throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
		}

		String encoded = passwordEncoder.encode(request.password());
		userRepository.save(User.ofLocal(request.email(), encoded, request.name()));
	}

	@Transactional(readOnly = true)
	public LoginResponse login(LoginRequest request) {
		User user = userRepository.findByEmail(request.email())
				.orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

		// 소셜 전용 계정은 비밀번호가 없다. 여기서 막지 않으면 matches()에 null이 넘어간다.
		if (!user.hasPassword()) {
			throw new BusinessException(ErrorCode.LOGIN_FAILED);
		}

		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.LOGIN_FAILED);
		}

		String token = jwtProvider.createAccessToken(user.getId(), user.getRole());
		return new LoginResponse(token, jwtProvider.getAccessTokenExpireMillis() / 1000, user.getName());
	}
}
