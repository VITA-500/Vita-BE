package com.vita.auth.entity;

import com.vita.auth.AuthProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * user_oauth 테이블. 한 사용자가 여러 소셜 계정을 연결할 수 있도록 1:N으로 분리했다.
 *
 * <p>소셜 로그인의 식별 키는 이메일이 아니라 (provider, providerId)다 — 이메일은 제공자가
 * 주지 않을 수도 있고 사용자가 바꿀 수도 있어서 식별자로 쓸 수 없다.
 */
@Entity
@Table(name = "user_oauths")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserOAuth {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "auth_provider", nullable = false)
	private AuthProvider provider;

	/** 카카오는 숫자 id를 주지만 구글·네이버는 문자열이라 String으로 통일한다. */
	@Column(name = "provider_id", nullable = false)
	private String providerId;

	private UserOAuth(User user, AuthProvider provider, String providerId) {
		this.user = user;
		this.provider = provider;
		this.providerId = providerId;
	}

	/** 이미 저장된 User에 소셜 연결을 새로 만든다. */
	public static UserOAuth of(User user, AuthProvider provider, String providerId) {
		return new UserOAuth(user, provider, providerId);
	}
}
