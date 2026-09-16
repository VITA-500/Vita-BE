package com.vita.auth.service;

import com.vita.auth.dto.LoginRequest;
import com.vita.auth.dto.LoginResponse;
import com.vita.auth.dto.SignupRequest;
import com.vita.auth.dto.SignupResponse;
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
	public SignupResponse signup(SignupRequest request) {
		if (userRepository.existsByEmail(request.email())) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}

		String encoded = passwordEncoder.encode(request.password());
		User saved = userRepository.save(User.ofLocal(request.email(), encoded, request.name()));
		return SignupResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public LoginResponse login(LoginRequest request) {
		User user = userRepository.findByEmail(request.email())
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

		// 소셜 전용 계정은 비밀번호가 없다. 여기서 막지 않으면 matches()에 null이 넘어간다.
		if (!user.hasPassword()) {
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}

		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}

		String token = jwtProvider.createAccessToken(user.getId(), user.getRole());
		return LoginResponse.of(token, user);
	}
}
