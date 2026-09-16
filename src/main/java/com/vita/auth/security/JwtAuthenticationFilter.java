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
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		}

		chain.doFilter(request, response);
	}

	private String resolveToken(HttpServletRequest request) {
		String header = request.getHeader(HEADER);
		if (header != null && header.startsWith(PREFIX)) {
			String token = header.substring(PREFIX.length()).trim();
			return token.isEmpty() ? null : token;
		}
		return null;
	}
}
