package com.vita.auth.service;

import com.vita.auth.PasswordPolicy;
import com.vita.auth.dto.LoginRequest;
import com.vita.auth.dto.LoginResult;
import com.vita.auth.dto.SignupRequest;
import com.vita.auth.dto.SignupResponse;
import com.vita.auth.entity.User;
import com.vita.auth.repository.UserRepository;
import com.vita.auth.security.RefreshTokenStore;
import com.vita.auth.security.TokenIssuer;
import com.vita.auth.security.TokenIssuer.IssuedTokens;
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
	private final TokenIssuer tokenIssuer;
	private final RefreshTokenStore refreshTokenStore;

	@Transactional
	public SignupResponse signup(SignupRequest request) {
		PasswordPolicy.validate(request.password());

		if (userRepository.existsByEmail(request.email())) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}

		String encoded = passwordEncoder.encode(request.password());
		User saved = userRepository.save(User.ofLocal(request.email(), encoded, request.name()));
		return SignupResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public LoginResult login(LoginRequest request) {
		User user = userRepository.findByEmail(request.email())
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

		// 소셜 전용 계정은 비밀번호가 없다. 여기서 막지 않으면 matches()에 null이 넘어간다.
		if (!user.hasPassword()) {
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}

		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}

		return LoginResult.of(tokenIssuer.issue(user.getId(), user.getRole()), user);
	}

	/**
	 * refreshToken으로 두 토큰을 새로 발급한다. 쓴 refreshToken은 즉시 폐기된다(rotation).
	 *
	 * <p>교체하는 이유 — 탈취된 refreshToken이 한 번 쓰이면, 원래 사용자의 다음 재발급이
	 * 실패하면서 재로그인하게 된다. 교체하지 않으면 탈취자가 만료(14일)까지 조용히 쓸 수 있다.
	 *
	 * <p>role은 토큰이 아니라 DB에서 다시 읽는다. 권한이 바뀌었거나 탈퇴한 사용자에게
	 * 이전 권한으로 토큰을 이어서 발급하지 않기 위함이다.
	 */
	@Transactional(readOnly = true)
	public IssuedTokens refresh(String refreshToken) {
		Long userId = refreshTokenStore.consume(refreshToken)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
		return tokenIssuer.issue(user.getId(), user.getRole());
	}

	/** 로그아웃 — Redis에서 refreshToken을 지워 더는 재발급받을 수 없게 한다. */
	public void logout(String refreshToken) {
		refreshTokenStore.revoke(refreshToken);
	}
}
