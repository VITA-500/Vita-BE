package com.vita.auth.repository;

import com.vita.auth.AuthProvider;
import com.vita.auth.entity.UserOAuth;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** user_oauth 조회. 소셜 로그인 식별과 연결 계정 조회에 쓴다. */
public interface UserOAuthRepository extends JpaRepository<UserOAuth, Long> {

	/** 소셜 로그인 식별 — 이 조합으로 users.id를 찾는다. */
	Optional<UserOAuth> findByProviderAndProviderId(AuthProvider provider, String providerId);

	/** 연결 여부만 필요할 때. 엔티티를 만들지 않아 findBy...보다 가볍다. */
	boolean existsByProviderAndProviderId(AuthProvider provider, String providerId);

	/** 한 사용자가 연결해둔 소셜 계정 전부 — 마이페이지의 연결 목록용. */
	List<UserOAuth> findAllByUserId(Long userId);
}
