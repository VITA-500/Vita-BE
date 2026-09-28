package com.vita.auth.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 헤더의 JWT를 검증해서 SecurityContext에 인증 정보를 채운다.
 *
 * <p>토큰이 없거나 틀려도 여기서 요청을 끊지 않고 그냥 통과시킨다 — 이 필터의 역할은
 * "인증 정보를 채우는 것"이고, 막는 것은 뒤쪽 인가 단계가 SecurityConfig의 규칙대로
 * 처리한다. 여기서 막으면 회원가입·로그인 같은 공개 API도 못 쓰게 된다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String HEADER = "Authorization";
	private static final String PREFIX = "Bearer ";

	private final JwtProvider jwtProvider;
	private final CookieUtil cookieUtil;

	@Override
	protected void doFilterInternal(
			@NonNull HttpServletRequest request,
			@NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {

		String token = resolveToken(request);

		if (token != null) {
			Claims claims = jwtProvider.parseOrNull(token);
			if (claims != null) {
				UserPrincipal principal = new UserPrincipal(
						jwtProvider.getUserId(claims), jwtProvider.getRole(claims));

				var authentication = new UsernamePasswordAuthenticationToken(
						principal, null, principal.getAuthorities());

				// 새 컨텍스트를 만들어 담는다. 기존 컨텍스트를 그대로 쓰면 OAuth 때문에 켜둔 세션에
				// 인증 정보가 저장되어, 토큰 쿠키를 지워도 세션만으로 인증이 유지된다(로그아웃 무력화).
				var context = SecurityContextHolder.createEmptyContext();
				context.setAuthentication(authentication);
				SecurityContextHolder.setContext(context);
			}
		}

		chain.doFilter(request, response);
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
