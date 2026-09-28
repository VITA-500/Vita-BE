package com.vita.auth.security;

import com.vita.auth.Role;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 인증된 요청의 주체. 컨트롤러에서 @AuthenticationPrincipal로 주입받는다.
 *
 * <p>회원과 비회원(게스트)을 모두 표현한다. 둘 중 하나만 값을 가진다 —
 * 회원이면 userId, 게스트면 guestId다. chat_sessions의 CHECK 제약과 같은 규칙이라,
 * 컨트롤러가 받은 값을 그대로 저장 계층에 넘길 수 있다.
 *
 * <p>토큰(또는 헤더)에 담긴 값만 들고 있어서 요청마다 DB를 조회하지 않는다.
 */
@Getter
public class UserPrincipal {

	/** 회원이면 값이 있고, 게스트면 null. */
	private final Long userId;

	/** 게스트면 값이 있고, 회원이면 null. */
	private final UUID guestId;

	/** 게스트는 권한 개념이 없어 null이다. getAuthorities()에서 분기한다. */
	private final Role role;

	private UserPrincipal(Long userId, UUID guestId, Role role) {
		this.userId = userId;
		this.guestId = guestId;
		this.role = role;
	}

	public static UserPrincipal ofMember(Long userId, Role role) {
		return new UserPrincipal(userId, null, role);
	}

	public static UserPrincipal ofGuest(UUID guestId) {
		return new UserPrincipal(null, guestId, null);
	}

	public boolean isGuest() {
		return userId == null;
	}

	/**
	 * hasRole("ADMIN")은 내부적으로 "ROLE_ADMIN"을 찾으므로 접두사를 붙여야 한다.
	 *
	 * <p>게스트에게 ROLE_USER를 주지 않는다 — 주면 회원 전용 API(마이페이지 등)가
	 * 헤더 하나로 열린다. 대신 ROLE_GUEST를 줘서 authenticated()는 통과하되
	 * 회원 권한이 필요한 곳에서는 막히게 한다.
	 */
	public Collection<? extends GrantedAuthority> getAuthorities() {
		String authority = isGuest() ? "ROLE_GUEST" : "ROLE_" + role.name();
		return List.of(new SimpleGrantedAuthority(authority));
	}
}
