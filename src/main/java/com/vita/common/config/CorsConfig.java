package com.vita.common.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 프론트(Vercel) ↔ 백엔드(EC2) 간 CORS 허용. 09_인프라구성.md: "프론트 배포 URL을 CORS 허용
 * 도메인으로 등록 필요". 도메인은 코드에 하드코딩하지 않고 환경변수(CORS_ALLOWED_ORIGINS,
 * 콤마로 여러 개 구분 가능)로 주입 — 프리뷰 URL이 추가되거나 도메인이 바뀌어도 재배포만 하면 됨.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

	private final List<String> allowedOrigins;

	public CorsConfig(@Value("${cors.allowed-origins}") String allowedOrigins) {
		this.allowedOrigins = List.of(allowedOrigins.split(","));
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**")
				.allowedOrigins(allowedOrigins.toArray(new String[0]))
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*")
				// JWT를 HttpOnly 쿠키로 갈지 아직 미정(FE1-BE1 협의 예정) — 쿠키 방식으로 정해지면
				// 브라우저가 쿠키를 실어 보내려면 이 설정이 필요해서 미리 켜둠. body 방식으로 가도 무해함.
				.allowCredentials(true);
	}
}
