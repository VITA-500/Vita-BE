package com.vita.auth.security;

import com.vita.auth.Role;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 인증된 사용자. 컨트롤러에서 @AuthenticationPrincipal로 주입받는다.
 *
 * <p>토큰에 담긴 값만 들고 있어서 요청마다 DB를 조회하지 않는다.
 */
@Getter
public class UserPrincipal {

	private final Long userId;
	private final Role role;

	public UserPrincipal(Long userId, Role role) {
		this.userId = userId;
		this.role = role;
	}

	/** hasRole("ADMIN")은 내부적으로 "ROLE_ADMIN"을 찾으므로 접두사를 붙여야 한다. */
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}
}
