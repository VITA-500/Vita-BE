package com.vita.auth.controller;

import com.vita.auth.dto.MyPageResponse;
import com.vita.auth.dto.MyPageUpdateRequest;
import com.vita.auth.dto.MyPageUpdateResponse;
import com.vita.auth.security.UserPrincipal;
import com.vita.auth.service.UserService;
import com.vita.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사용자", description = "내 정보 조회 / 수정")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

	private final UserService userService;

	@Operation(summary = "내 정보 조회",
			description = "이메일과 전화번호는 마스킹해서 내려간다. 카카오 가입자는 email이 null일 수 있다.",
			security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조회 성공"),
			@ApiResponse(responseCode = "401", description = "토큰 없음 · 만료 · 위조",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@GetMapping("/me")
	public MyPageResponse getMyPage(@AuthenticationPrincipal UserPrincipal principal) {
		// userId를 파라미터로 받지 않는다 — 받으면 남의 id를 넣어 조회하는 취약점이 생긴다.
		return userService.getMyPage(principal.getUserId());
	}

	@Operation(summary = "내 정보 수정",
			description = "보낸 필드만 수정된다(PATCH). 이메일·권한은 수정할 수 없다. "
					+ "소셜 전용 계정(hasPassword=false)은 비밀번호를 바꿀 수 없다.",
			security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "수정 성공"),
			@ApiResponse(responseCode = "400", description = "입력값 검증 실패 · 소셜 전용 계정의 비밀번호 변경",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "토큰 없음 · 만료 · 위조",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@PatchMapping("/me")
	public MyPageUpdateResponse updateMyPage(
			@AuthenticationPrincipal UserPrincipal principal,
			@Valid @RequestBody MyPageUpdateRequest request) {

		return userService.updateMyPage(principal.getUserId(), request);
	}
}
