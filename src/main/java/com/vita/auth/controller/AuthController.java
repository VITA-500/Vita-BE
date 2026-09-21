package com.vita.auth.controller;

import com.vita.auth.dto.CsrfTokenResponse;
import com.vita.auth.dto.LoginRequest;
import com.vita.auth.dto.LoginResponse;
import com.vita.auth.dto.LoginResult;
import com.vita.auth.security.CookieUtil;
import com.vita.auth.security.JwtProvider;
import com.vita.auth.dto.SignupRequest;
import com.vita.auth.dto.SignupResponse;
import com.vita.auth.service.AuthService;
import com.vita.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "자체 회원가입 / 로그인")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final CookieUtil cookieUtil;
	private final JwtProvider jwtProvider;

	@Operation(summary = "자체 회원가입",
			description = "이메일과 비밀번호로 가입한다. 비밀번호는 bcrypt로 해싱해서 저장하며 평문은 보관하지 않는다.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "가입 성공"),
			@ApiResponse(responseCode = "400", description = "입력값 검증 실패",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "이미 가입된 이메일",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@PostMapping("/signup")
	public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
		SignupResponse response = authService.signup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@Operation(summary = "자체 로그인",
			description = "성공하면 accessToken을 HttpOnly 쿠키로 내려준다. 프론트는 토큰을 직접 다루지 않고, "
					+ "이후 요청에 withCredentials 옵션만 켜면 브라우저가 자동으로 실어 보낸다.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "로그인 성공"),
			@ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호 불일치",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		LoginResult result = authService.login(request);

		ResponseCookie cookie = cookieUtil.create(
				result.accessToken(),
				Duration.ofMillis(jwtProvider.getAccessTokenExpireMillis()));

		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookie.toString())
				.body(result.response());
	}

	@Operation(summary = "로그아웃",
			description = "인증 쿠키를 만료시킨다. 서버에 저장된 상태가 없으므로 쿠키 삭제가 곧 로그아웃이다.")
	@ApiResponse(responseCode = "200", description = "로그아웃 성공")
	@PostMapping("/logout")
	public ResponseEntity<Void> logout() {
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookieUtil.expire().toString())
				.build();
	}

	/**
	 * CSRF 토큰 발급.
	 *
	 * <p>인증을 HttpOnly 쿠키로 하면서 CSRF 검사가 필요해졌는데(SecurityConfig 참고), 프론트가
	 * 그 토큰을 얻을 창구가 없었다. 쿠키로만 내려주면 도메인이 다른 프론트(Vercel)는 자바스크립트로
	 * 읽을 수 없어서, body로도 함께 내려준다.
	 *
	 * <p>파라미터의 CsrfToken은 Spring Security가 요청 속성에 넣어둔 값이다. 이 메서드가 값을
	 * 꺼내는 순간 CookieCsrfTokenRepository가 XSRF-TOKEN 쿠키를 함께 내려보낸다 — 토큰은
	 * 실제로 조회될 때만 발급되는 지연 방식이라, 이 엔드포인트가 없으면 쿠키도 생기지 않는다.
	 */
	@Operation(summary = "CSRF 토큰 발급",
			description = "로그인 이후의 쓰기 요청(POST/PATCH/DELETE)에 필요한 CSRF 토큰을 발급한다. "
					+ "응답 body의 token을 headerName 헤더에 실어 보내면 된다. "
					+ "XSRF-TOKEN 쿠키도 함께 내려가지만, 프론트와 백엔드의 도메인이 다르면 "
					+ "자바스크립트가 쿠키를 읽을 수 없으므로 body 값을 쓴다.")
	@ApiResponse(responseCode = "200", description = "발급 성공")
	@GetMapping("/csrf")
	public CsrfTokenResponse csrf(CsrfToken csrfToken) {
		return CsrfTokenResponse.of(csrfToken.getToken(), csrfToken.getHeaderName());
	}
}
