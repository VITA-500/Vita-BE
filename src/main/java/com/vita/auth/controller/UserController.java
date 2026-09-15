package com.vita.auth.controller;

import com.vita.auth.dto.MyPageResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사용자", description = "내 정보 조회")
@RestController
@RequestMapping("/api/users")
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
}
