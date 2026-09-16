package com.vita.auth.security;

import com.vita.auth.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 발급과 검증.
 *
 * <p>payload에는 userId와 role만 넣는다. JWT는 암호화가 아니라 서명이라 payload를 누구나
 * Base64 디코딩해서 읽을 수 있다 — 이메일 같은 개인정보를 넣으면 클라이언트 저장소와
 * 로그에 평문으로 남는다.
 */
@Slf4j
@Component
public class JwtProvider {

	private final SecretKey key;

	@Getter
	private final long accessTokenExpireMillis;

	public JwtProvider(
			@Value("${jwt.secret}") String secret,
			@Value("${jwt.access-token-expire-millis}") long accessTokenExpireMillis) {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < 32) {
			throw new IllegalStateException(
					"jwt.secret은 32바이트 이상이어야 합니다 (HS256 요구사항). 현재 " + keyBytes.length + "바이트");
		}
		this.key = Keys.hmacShaKeyFor(keyBytes);
		this.accessTokenExpireMillis = accessTokenExpireMillis;
	}

	public String createAccessToken(Long userId, Role role) {
		Date now = new Date();
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.claim("role", role.name())
				.issuedAt(now)
				.expiration(new Date(now.getTime() + accessTokenExpireMillis))
				.signWith(key)
				.compact();
	}

	/** 유효하면 Claims, 아니면 null. 만료와 위조를 호출부에서 구분할 필요가 없어 예외를 밖으로 던지지 않는다. */
	public Claims parseOrNull(String token) {
		try {
			return Jwts.parser()
					.verifyWith(key)
					.build()
					.parseSignedClaims(token)
					.getPayload();
		} catch (ExpiredJwtException e) {
			log.debug("만료된 토큰");
			return null;
		} catch (JwtException | IllegalArgumentException e) {
			log.debug("유효하지 않은 토큰: {}", e.getClass().getSimpleName());
			return null;
		}
	}

	public Long getUserId(Claims claims) {
		return Long.valueOf(claims.getSubject());
	}

	public Role getRole(Claims claims) {
		return Role.valueOf(claims.get("role", String.class));
	}
}
