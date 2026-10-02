package com.vita.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * refreshToken 발급·교체·폐기. Redis에 "토큰 → userId"로 저장한다.
 *
 * <p>토큰은 JWT가 아니라 무작위 문자열이다. Redis에 있어야만 유효하므로 서명이 필요 없고,
 * 로그아웃하거나 교체하면 즉시 무효가 된다 — 서명만으로 유효성을 판단하는 JWT는 만료 전에
 * 무효화할 방법이 없다.
 *
 * <p>Redis 키에는 토큰 원문이 아니라 SHA-256 해시를 쓴다. Redis 내용이 유출돼도 그 값으로
 * 재발급을 받을 수 없게 하기 위함이다(비밀번호를 해시로 저장하는 것과 같은 이유).
 */
@Component
public class RefreshTokenStore {

	private static final String KEY_PREFIX = "refresh:";
	private static final int TOKEN_BYTES = 32;

	private final StringRedisTemplate redis;
	private final SecureRandom random = new SecureRandom();

	@Getter
	private final Duration ttl;

	public RefreshTokenStore(
			StringRedisTemplate redis,
			@Value("${jwt.refresh-token-expire-millis}") long refreshTokenExpireMillis) {
		this.redis = redis;
		this.ttl = Duration.ofMillis(refreshTokenExpireMillis);
	}

	/** 새 refreshToken을 만들어 저장하고 원문을 돌려준다. 원문은 쿠키로만 나가고 서버에는 해시만 남는다. */
	public String issue(Long userId) {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		redis.opsForValue().set(key(token), String.valueOf(userId), ttl);
		return token;
	}

	/**
	 * 토큰을 꺼내면서 동시에 지운다(GETDEL). 재발급마다 토큰을 교체(rotation)하기 위함이다.
	 *
	 * <p>조회와 삭제를 따로 하면 같은 토큰으로 동시에 두 번 재발급 요청이 왔을 때 둘 다 통과할 수
	 * 있다. 한 번에 처리하면 한 요청만 userId를 받고 나머지는 빈 값을 받는다.
	 */
	public Optional<Long> consume(String token) {
		String userId = redis.opsForValue().getAndDelete(key(token));
		return Optional.ofNullable(userId).map(Long::valueOf);
	}

	/** 로그아웃용. 없는 토큰이어도 조용히 넘어간다. */
	public void revoke(String token) {
		redis.delete(key(token));
	}

	private String key(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8));
			return KEY_PREFIX + HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			// 모든 JVM이 SHA-256을 제공해야 한다(Java SE 명세). 여기 오면 실행 환경 자체가 잘못된 것이다.
			throw new IllegalStateException(e);
		}
	}
}
