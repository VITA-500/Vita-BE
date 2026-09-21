package com.vita.auth.oauth;

import com.vita.auth.OAuth2AuthenticationProcessingException;
import com.vita.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 소셜 로그인 실패 시 에러 코드를 쿼리 파라미터로 실어 프론트로 돌려보낸다.
 *
 * <p>성공과 마찬가지로 리다이렉트라 JSON을 줄 수 없다. FE는 error 값으로 안내 문구를 고른다.
 */
@Slf4j
@Component
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

	@Value("${app.oauth2.redirect-uri}")
	private String redirectUri;

	/** 에러 코드만 쿼리 파라미터로 붙여 프론트로 리다이렉트한다. 상세 사유는 로그에만 남긴다. */
	@Override
	public void onAuthenticationFailure(
			HttpServletRequest request,
			HttpServletResponse response,
			AuthenticationException exception) throws IOException {

		ErrorCode errorCode = resolve(exception);
		// 예외 메시지를 그대로 노출하지 않는다 (08_개발표준 3절). 로그에만 남긴다.
		log.warn("소셜 로그인 실패: {}", errorCode, exception);

		String target = UriComponentsBuilder.fromUriString(redirectUri)
				.queryParam("error", errorCode.name())
				.build()
				.toUriString();

		getRedirectStrategy().sendRedirect(request, response, target);
	}

	/** 우리가 던진 도메인 예외면 그 코드를, 스프링이 던진 예외면 일반 실패 코드를 쓴다. */
	private ErrorCode resolve(AuthenticationException exception) {
		if (exception instanceof OAuth2AuthenticationProcessingException e) {
			return e.getErrorCode();
		}
		return ErrorCode.OAUTH_AUTHENTICATION_FAILED;
	}
}
