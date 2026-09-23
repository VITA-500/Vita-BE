package com.vita.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI — http://localhost:8080/swagger-ui/index.html
 *
 * <p>우측 상단 Authorize에 로그인으로 받은 accessToken을 넣으면 이후 요청에 자동으로 붙는다.
 * 소셜 로그인은 리다이렉트 방식이라 Swagger에서 테스트할 수 없고 브라우저에서 직접 호출해야 한다.
 *
 * <p>비회원(게스트) 요청은 Authorize의 guestId 칸에 UUID를 넣어 시험한다. 이 헤더는
 * 인증 필터에서만 읽고 컨트롤러 파라미터로 선언하지 않아서, 여기에 등록하지 않으면
 * Swagger에 입력란이 생기지 않아 게스트 API를 시험할 방법이 없다.
 */
@Configuration
public class SwaggerConfig {

	private static final String BEARER = "bearerAuth";
	private static final String GUEST = "guestId";
	/** 비회원 식별자 헤더. JwtAuthenticationFilter가 읽는 이름과 같아야 한다. */
	private static final String GUEST_HEADER = "X-Guest-Id";
	private static final String CSRF = "csrfToken";
	/** 스프링 시큐리티의 CookieCsrfTokenRepository가 기대하는 헤더 이름. */
	private static final String CSRF_HEADER = "X-XSRF-TOKEN";

	@Bean
	public OpenAPI openAPI() {
		SecurityScheme bearerScheme = new SecurityScheme()
				.type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.bearerFormat("JWT")
				.description("로그인 응답의 accessToken 값을 그대로 붙여넣는다 ('Bearer ' 접두사는 자동으로 붙음)");

		SecurityScheme guestScheme = new SecurityScheme()
				.type(SecurityScheme.Type.APIKEY)
				.in(SecurityScheme.In.HEADER)
				.name(GUEST_HEADER)
				.description("비회원 테스트용 UUID (예: 123e4567-e89b-12d3-a456-426614174000). "
						+ "형식이 UUID가 아니면 게스트로 인정되지 않아 401이 난다. "
						+ "로그인 상태에서는 무시된다.");

		SecurityScheme csrfScheme = new SecurityScheme()
				.type(SecurityScheme.Type.APIKEY)
				.in(SecurityScheme.In.HEADER)
				.name(CSRF_HEADER)
				.description("쿠키로 로그인한 상태에서 쓰기 요청(POST/PUT/DELETE)을 보낼 때 필요하다. "
						+ "GET /auth/csrf 를 먼저 실행해 응답의 token 값을 여기에 붙여넣는다. "
						+ "없으면 403이 나고 응답 본문이 비어 있다 — 시큐리티 필터가 컨트롤러 전에 막기 때문이다. "
						+ "로그인 쿠키가 없는 게스트 요청에는 필요 없다.");

		return new OpenAPI()
				.info(new Info()
						.title("VITA API")
						.version("v1")
						.description("AI 상담 서비스 VITA 백엔드 API"))
				.components(new Components()
						.addSecuritySchemes(BEARER, bearerScheme)
						.addSecuritySchemes(GUEST, guestScheme)
						.addSecuritySchemes(CSRF, csrfScheme))
				// 둘을 각각 등록한다. 하나의 SecurityRequirement에 묶으면 "둘 다 필요"가 되어
				// 회원 요청에도 게스트 헤더를 요구하는 문서가 된다.
				.addSecurityItem(new SecurityRequirement().addList(BEARER))
				.addSecurityItem(new SecurityRequirement().addList(GUEST))
				.addSecurityItem(new SecurityRequirement().addList(CSRF));
	}
}
