package com.vita.auth.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청의 JWT(또는 게스트 헤더)를 확인해서 SecurityContext에 주체 정보를 채운다.
 *
 * <p>토큰이 없거나 틀려도 여기서 요청을 끊지 않고 그냥 통과시킨다 — 이 필터의 역할은
 * "인증 정보를 채우는 것"이고, 막는 것은 뒤쪽 인가 단계가 SecurityConfig의 규칙대로
 * 처리한다. 여기서 막으면 회원가입·로그인 같은 공개 API도 못 쓰게 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String HEADER = "Authorization";
	private static final String PREFIX = "Bearer ";
	/** 비회원 식별자. 프론트가 최초 진입 시 UUID를 만들어 localStorage에 보관하고 매 요청 보낸다. */
	private static final String GUEST_HEADER = "X-Guest-Id";

	private final JwtProvider jwtProvider;
	private final CookieUtil cookieUtil;

	@Override
	protected void doFilterInternal(
			@NonNull HttpServletRequest request,
			@NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {

		UserPrincipal principal = resolveMember(request);
		if (principal == null) {
			// 회원이 아닐 때만 게스트로 본다. 로그인 상태에서 X-Guest-Id가 함께 와도
			// 무시된다 — 프론트가 로그인 여부로 헤더를 분기하지 않아도 되게 하기 위함이다.
			principal = resolveGuest(request);
		}

		if (principal != null) {
			var authentication = new UsernamePasswordAuthenticationToken(
					principal, null, principal.getAuthorities());

			// 새 컨텍스트를 만들어 담는다. 기존 컨텍스트를 그대로 쓰면 OAuth 때문에 켜둔 세션에
			// 인증 정보가 저장되어, 토큰 쿠키를 지워도 세션만으로 인증이 유지된다(로그아웃 무력화).
			var context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authentication);
			SecurityContextHolder.setContext(context);
		}

		chain.doFilter(request, response);
	}

	/** 토큰이 유효하면 회원 주체를, 아니면 null. */
	private UserPrincipal resolveMember(HttpServletRequest request) {
		String token = resolveToken(request);
		if (token == null) {
			return null;
		}
		Claims claims = jwtProvider.parseOrNull(token);
		if (claims == null) {
			return null;
		}
		return UserPrincipal.ofMember(jwtProvider.getUserId(claims), jwtProvider.getRole(claims));
	}

	/**
	 * X-Guest-Id 헤더로 비회원을 식별한다(FE 협의, 2026-09-22).
	 *
	 * <p>이 값은 프론트가 만들어 보내는 것이라 위조할 수 있다 — 비회원을 인증할 방법은
	 * 없으므로 막지 않고, 대신 게스트에게는 ROLE_GUEST만 줘서 회원 API에 닿지 못하게 한다.
	 *
	 * <p>UUID로 파싱되지 않으면 게스트로 인정하지 않는다. 형식을 강제해야 DB(uuid 컬럼)에
	 * 넣을 수 있고, 짧은 문자열이 섞여 들어와 충돌하는 일도 막는다.
	 */
	private UserPrincipal resolveGuest(HttpServletRequest request) {
		String header = request.getHeader(GUEST_HEADER);
		if (header == null || header.isBlank()) {
			return null;
		}
		try {
			return UserPrincipal.ofGuest(UUID.fromString(header.trim()));
		} catch (IllegalArgumentException e) {
			log.debug("X-Guest-Id가 UUID 형식이 아님");
			return null;
		}
	}

	/**
	 * 쿠키를 우선 보고, 없으면 Authorization 헤더를 본다.
	 *
	 * <p>브라우저는 HttpOnly 쿠키로 인증하지만(FE1 협의 결과), Swagger의 Authorize 버튼과
	 * curl 같은 도구는 헤더로 보낸다. 둘 다 받아야 개발·테스트가 막히지 않는다.
	 */
	private String resolveToken(HttpServletRequest request) {
		return cookieUtil.read(request).orElseGet(() -> resolveFromHeader(request));
	}

	private String resolveFromHeader(HttpServletRequest request) {
		String header = request.getHeader(HEADER);
		if (header != null && header.startsWith(PREFIX)) {
			String token = header.substring(PREFIX.length()).trim();
			return token.isEmpty() ? null : token;
		}
		return null;
	}
}
