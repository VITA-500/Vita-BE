package com.vita.auth.security;

import com.vita.common.exception.ErrorCode;
import com.vita.common.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import java.nio.charset.StandardCharsets;

/**
 * 인증·인가 설정.
 *
 * <p>CORS는 common/config/CorsConfig(WebMvcConfigurer)가 담당한다. 여기서는
 * cors(withDefaults 상당)만 켜서 그 설정이 시큐리티 필터체인에도 적용되게 한다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final ObjectMapper objectMapper;

	/**
	 * 관리자 전용 경로. 04_API명세서 기준 경로는 /admin/** 이지만, 접두사 표기가 흔들려도
	 * 인가가 통째로 비활성화되지 않도록 /api/admin/** 도 함께 막는다 — 실제로 컨트롤러가
	 * /admin/stores 로 매핑되어 있는데 규칙만 /api/admin/** 이라 hasRole이 적용되지 않고
	 * anyRequest().authenticated() 로 떨어진 사고가 있었다.
	 */
	private static final String[] ADMIN_PATHS = {
			"/admin/**",
			"/api/admin/**"
	};

	/** 인증 없이 열어둘 경로. 구체적인 경로를 먼저 나열하고 anyRequest()는 마지막에 둔다. */
	private static final String[] PUBLIC_PATHS = {
			"/auth/**",
			"/search/**",
			"/stores/**",
			"/swagger-ui/**",
			"/v3/api-docs/**",
			"/swagger-ui.html"
	};

	@Bean
	public PasswordEncoder passwordEncoder() {
		// bcrypt는 의도적으로 느리게 설계되어 무차별 대입을 어렵게 만들고, 솔트를 자동으로 넣는다.
		return new BCryptPasswordEncoder();
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				// JWT를 헤더로 받으므로 브라우저가 쿠키를 자동 전송하는 CSRF 공격이 성립하지 않는다.
				// HttpOnly 쿠키 방식으로 바뀌면 이 설정을 되돌려야 한다.
				.csrf(csrf -> csrf.disable())
				.cors(cors -> {
				})
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

				// 위에서부터 먼저 매칭되는 규칙이 이긴다. 관리자 경로를 가장 먼저 두어,
				// PUBLIC_PATHS 패턴이 넓어지더라도 관리자 API가 열리지 않게 한다.
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(ADMIN_PATHS).hasRole("ADMIN")
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.anyRequest().authenticated())

				.exceptionHandling(ex -> ex
						// 인증 실패 → 401. 기본 동작은 로그인 페이지 리다이렉트라 REST API에 맞지 않는다.
						.authenticationEntryPoint((req, res, e) ->
								writeError(res, ErrorCode.UNAUTHORIZED))
						// 인가 실패 → 403
						.accessDeniedHandler((req, res, e) ->
								writeError(res, ErrorCode.FORBIDDEN)))

				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	private void writeError(HttpServletResponse response, ErrorCode errorCode) throws java.io.IOException {
		response.setStatus(errorCode.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
	}
}
