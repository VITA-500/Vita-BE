package com.vita.auth.repository;

import com.vita.auth.AuthProvider;
import com.vita.auth.entity.UserOAuth;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** user_oauth 조회. 소셜 로그인 식별과 연결 계정 조회에 쓴다. */
public interface UserOAuthRepository extends JpaRepository<UserOAuth, Long> {

	/**
	 * 소셜 로그인 식별 — 이 조합으로 회원을 찾는다.
	 *
	 * <p>user를 fetch join으로 함께 가져온다. UserOAuth.user는 LAZY라 그냥 조회하면 프록시가
	 * 실리는데, 이 결과를 쓰는 OAuth2SuccessHandler는 트랜잭션 밖에서 동작해
	 * LazyInitializationException이 난다. 신규 가입은 save()가 실제 엔티티를 돌려주므로
	 * 멀쩡하고, 기존 사용자의 재로그인만 실패하는 형태로 드러났다.
	 */
	@Query("select uo from UserOAuth uo join fetch uo.user where uo.provider = :provider and uo.providerId = :providerId")
	Optional<UserOAuth> findByProviderAndProviderId(
			@Param("provider") AuthProvider provider,
			@Param("providerId") String providerId);

	/** 연결 여부만 필요할 때. 엔티티를 만들지 않아 findBy...보다 가볍다. */
	boolean existsByProviderAndProviderId(AuthProvider provider, String providerId);

	/** 한 사용자가 연결해둔 소셜 계정 전부 — 마이페이지의 연결 목록용. */
	List<UserOAuth> findAllByUserId(Long userId);
}
