package com.vita.auth.controller;

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
}
