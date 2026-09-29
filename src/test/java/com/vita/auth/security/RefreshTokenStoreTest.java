package com.vita.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RefreshTokenStoreTest {

	private static final long TTL_MILLIS = 1_209_600_000L;

	private StringRedisTemplate redis;
	private ValueOperations<String, String> ops;
	private RefreshTokenStore store;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		redis = mock(StringRedisTemplate.class);
		ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		store = new RefreshTokenStore(redis, TTL_MILLIS);
	}

	@Test
	@DisplayName("발급 시 토큰 원문이 아니라 해시를 키로, 만료 시간과 함께 저장한다")
	void storesHashedKeyWithTtl() {
		String token = store.issue(7L);

		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		verify(ops).set(key.capture(), eq("7"), eq(Duration.ofMillis(TTL_MILLIS)));
		assertThat(key.getValue()).startsWith("refresh:").doesNotContain(token);
	}

	@Test
	@DisplayName("발급할 때마다 다른 토큰이 나온다")
	void issuesUniqueTokens() {
		assertThat(store.issue(1L)).isNotEqualTo(store.issue(1L));
	}

	@Test
	@DisplayName("사용하면 꺼내면서 동시에 지운다(GETDEL) — 같은 토큰으로 두 번 재발급할 수 없다")
	void consumeIsAtomicGetAndDelete() {
		String token = store.issue(7L);
		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		verify(ops).set(key.capture(), anyString(), any(Duration.class));
		when(ops.getAndDelete(key.getValue())).thenReturn("7").thenReturn(null);

		assertThat(store.consume(token)).contains(7L);
		assertThat(store.consume(token)).isEmpty();
	}

	@Test
	@DisplayName("모르는 토큰은 빈 값이다")
	void unknownTokenIsEmpty() {
		assertThat(store.consume("unknown")).isEmpty();
	}
}
