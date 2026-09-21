package com.vita.auth.security;

import com.vita.auth.oauth.CustomOAuth2UserService;
import com.vita.auth.oauth.OAuth2FailureHandler;
import com.vita.auth.oauth.OAuth2SuccessHandler;
import com.vita.common.exception.ErrorCode;
import com.vita.common.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
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
	private final CustomOAuth2UserService customOAuth2UserService;
	private final OAuth2SuccessHandler oAuth2SuccessHandler;
	private final OAuth2FailureHandler oAuth2FailureHandler;
	/**
	 * OAuth 키가 주입되지 않으면(= oauth 프로파일이 꺼져 있으면) 이 Bean이 없다.
	 * ObjectProvider로 받아 존재할 때만 oauth2Login을 켠다 — 키 없이도 서버가 뜨게 하기 위함.
	 */
	private final ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository;

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

	/**
	 * CSRF 검사를 면제할 경로. 아직 인증 쿠키가 없어 위조할 대상 자체가 없는 요청들이다.
	 * Swagger UI에서 로그인을 시험할 수 있게 하려는 목적도 있다.
	 *
	 * <p>토큰을 발급받는 창구는 GET /auth/csrf다(AuthController). 프론트는 앱이 시작될 때
	 * 한 번 호출해 body의 token을 보관했다가, 이후 쓰기 요청마다 X-XSRF-TOKEN 헤더에 싣는다.
	 */
	private static final String[] CSRF_EXEMPT_PATHS = {
			"/auth/**",
			"/oauth2/**",
			"/login/oauth2/**"
	};

	/** 인증 없이 열어둘 경로. 구체적인 경로를 먼저 나열하고 anyRequest()는 마지막에 둔다. */
	private static final String[] PUBLIC_PATHS = {
			"/auth/**",
			// 소셜 로그인 진입·콜백. Spring Security가 처리하는 경로라 컨트롤러가 없다.
			"/oauth2/**",
			"/login/oauth2/**",
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
				// 인증을 HttpOnly 쿠키로 하면 브라우저가 요청마다 자동으로 실어 보내므로 CSRF가 성립한다.
				// 쿠키에 담긴 CSRF 토큰과 헤더로 온 값을 대조해 막는다 — 다른 사이트는 우리 쿠키를
				// 읽어서 헤더에 넣을 수 없기 때문에 위조 요청을 걸러낼 수 있다.
				// 로그인·회원가입·소셜 로그인은 아직 인증 쿠키가 없는 상태라 제외한다.
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
						.ignoringRequestMatchers(CSRF_EXEMPT_PATHS))
				.cors(cors -> {
				})
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				// JWT는 세션이 필요 없지만, OAuth2의 state(CSRF 방어) 검증이 세션을 쓴다.
				// STATELESS로 두면 소셜 로그인 콜백에서 authorization_request_not_found가 난다.
				// 세션은 OAuth 흐름 동안만 쓰이고, 인증 상태는 저장하지 않는다(아래 securityContext 설정).
				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

				// 인증 결과를 세션에 저장하지 않는다. 저장하면 토큰 쿠키를 지워도 세션에 남은 인증으로
				// 요청이 통과해 로그아웃이 무력화된다 — 인증은 매 요청 토큰으로만 판단해야 한다.
				// (OAuth의 state는 세션의 다른 영역을 쓰므로 이 설정과 무관하게 계속 동작한다.)
				.securityContext(context -> context
						.securityContextRepository(new NullSecurityContextRepository()))

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

		if (clientRegistrationRepository.getIfAvailable() != null) {
			http.oauth2Login(oauth -> oauth
					.userInfoEndpoint(endpoint -> endpoint.userService(customOAuth2UserService))
					.successHandler(oAuth2SuccessHandler)
					.failureHandler(oAuth2FailureHandler));
		}

		return http.build();
	}

	private void writeError(HttpServletResponse response, ErrorCode errorCode) throws java.io.IOException {
		response.setStatus(errorCode.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
	}
}
