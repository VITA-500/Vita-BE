package com.vita.auth.entity;

import com.vita.auth.Role;
import com.vita.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * users 테이블. 자체 가입과 소셜 가입이 한 테이블에 공존한다 (V1 마이그레이션 2절).
 *
 * <p>email과 passwordHash가 모두 NULL 허용인 이유:
 * <ul>
 *   <li>email — 카카오는 이메일을 제공하지 않을 수 있다. 로그인 식별은 email이 아니라
 *       (provider, providerId) 조합으로 하므로 없어도 계정이 성립한다.</li>
 *   <li>passwordHash — 소셜 전용 계정은 비밀번호가 없다.</li>
 * </ul>
 * 따라서 "자체 가입이면 비밀번호 필수"는 DB가 아니라 서비스 로직에서 보장한다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String email;

	@Column(name = "password_hash")
	private String passwordHash;

	private String name;

	private String phone;

	/** DB가 user_role ENUM 타입이라 그냥 STRING으로 두면 타입 불일치가 난다. */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "user_role")
	private Role role;

	@Builder
	private User(String email, String passwordHash, String name, String phone, Role role) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.name = name;
		this.phone = phone;
		this.role = role != null ? role : Role.USER;
	}

	/** 자체 가입. 권한은 요청값을 신뢰하지 않고 항상 USER로 고정한다. */
	public static User ofLocal(String email, String passwordHash, String name) {
		return User.builder()
				.email(email)
				.passwordHash(passwordHash)
				.name(name)
				.role(Role.USER)
				.build();
	}

	/**
	 * 소셜 가입. 비밀번호가 없고, 이메일도 제공자가 주지 않으면 null이다
	 * (로그인 식별은 user_oauth의 (provider, provider_id)가 담당한다).
	 */
	public static User ofSocial(String email, String name) {
		return User.builder()
				.email(email)
				.passwordHash(null)
				.name(name)
				.role(Role.USER)
				.build();
	}

	/** 비밀번호로 로그인할 수 있는 계정인지. 소셜 전용 계정은 false. */
	public boolean hasPassword() {
		return passwordHash != null;
	}

	/** 마이페이지 이름 변경. 호출 전에 검증을 마친 값만 넘어온다고 본다. */
	public void changeName(String name) {
		this.name = name;
	}

	/**
	 * 마이페이지 비밀번호 변경. 평문이 아니라 이미 해싱된 값을 받는다 — 엔티티가
	 * PasswordEncoder를 알 필요가 없고, 평문이 엔티티까지 흘러들지도 않는다.
	 */
	public void changePassword(String passwordHash) {
		this.passwordHash = passwordHash;
	}
}
