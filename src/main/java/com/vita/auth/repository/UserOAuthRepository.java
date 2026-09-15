package com.vita.auth.repository;

import com.vita.auth.entity.AuthProvider;
import com.vita.auth.entity.UserOAuth;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserOAuthRepository extends JpaRepository<UserOAuth, Long> {

	/** 소셜 로그인 식별 — 이 조합으로 users.id를 찾는다. */
	Optional<UserOAuth> findByProviderAndProviderId(AuthProvider provider, String providerId);

	boolean existsByProviderAndProviderId(AuthProvider provider, String providerId);

	List<UserOAuth> findAllByUserId(Long userId);
}
