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
 */
@Configuration
public class SwaggerConfig {

	private static final String BEARER = "bearerAuth";

	@Bean
	public OpenAPI openAPI() {
		SecurityScheme bearerScheme = new SecurityScheme()
				.type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.bearerFormat("JWT")
				.description("로그인 응답의 accessToken 값을 그대로 붙여넣는다 ('Bearer ' 접두사는 자동으로 붙음)");

		return new OpenAPI()
				.info(new Info()
						.title("VITA API")
						.version("v1")
						.description("AI 상담 서비스 VITA 백엔드 API"))
				.components(new Components().addSecuritySchemes(BEARER, bearerScheme))
				.addSecurityItem(new SecurityRequirement().addList(BEARER));
	}
}
