package com.vita.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.vita.auth.oauth.CustomOAuth2UserService;
import com.vita.auth.oauth.OAuth2FailureHandler;
import com.vita.auth.oauth.OAuth2SuccessHandler;
import com.vita.chat.controller.ChatSessionController;
import com.vita.chat.service.ChatSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.SessionManagementFilter;

/**
 * 로그인한 사용자의 요청마다 CSRF 토큰이 교체되지 않는지 검증한다.
 *
 * <p>SessionManagementFilter는 인증 정보가 세션에 없으면 그 요청을 "새 로그인"으로 보고
 * CSRF 토큰을 교체한다. 우리는 인증을 세션에 저장하지 않으므로(NullSecurityContextRepository)
 * 이 필터가 있으면 매 요청 토큰이 바뀌어, 로그인 후 첫 쓰기 요청만 성공하고 다음 요청부터
 * 403이 난다. sessionCreationPolicy()를 명시적으로 호출하기만 해도 이 필터가 켜지므로
 * 설정을 되돌리는 실수를 테스트로 막는다.
 */
@WebMvcTest(ChatSessionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class CsrfTokenRotationTest {

	@Autowired SecurityFilterChain filterChain;
	@MockBean ChatSessionService chatSessionService;
	@MockBean JwtProvider jwtProvider;
	@MockBean CookieUtil cookieUtil;
	@MockBean CustomOAuth2UserService oauthService;
	@MockBean OAuth2SuccessHandler successHandler;
	@MockBean OAuth2FailureHandler failureHandler;

	@Test
	@DisplayName("SessionManagementFilter가 없어 요청마다 CSRF 토큰이 교체되지 않는다")
	void noPerRequestCsrfRotation() {
		assertThat(filterChain.getFilters())
				.noneMatch(filter -> filter instanceof SessionManagementFilter);
	}
}
