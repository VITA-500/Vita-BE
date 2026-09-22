package com.vita.chat.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vita.auth.security.UserPrincipal;
import com.vita.chat.dto.ChatSessionClaimResponse;
import com.vita.chat.dto.ChatSessionCreateResponse;
import com.vita.chat.dto.ChatSessionListResponse;
import com.vita.chat.service.ChatSessionService;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.util.PrincipalUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/chat/sessions")
@RequiredArgsConstructor
public class ChatSessionController {

	private final ChatSessionService chatSessionService;
	
	@PostMapping
	public ResponseEntity<ChatSessionCreateResponse> createSession(
			@AuthenticationPrincipal UserPrincipal user
			){
		
		Long userId = user.getUserId();
		Long guestId = user.getGuestId();
		
		if (userId == null && guestId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED); // 로그인도 게스트도 아님
		}
		
		ChatSessionCreateResponse response = chatSessionService.createSession(userId, guestId);
		
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
	
	@GetMapping
	public ChatSessionListResponse getSessions(
			@AuthenticationPrincipal UserPrincipal user
			){
		if(user == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		
		ChatSessionListResponse response = chatSessionService.getSessions(user.getUserId());
		
		return response;
	}
	
	@PostMapping("/{sessionId}/claim")
	public ResponseEntity<ChatSessionClaimResponse> claimSession(
			@PathVariable Long sessionId,
			@AuthenticationPrincipal UserPrincipal user) {

		Long userId = PrincipalUtils.userIdOf(user);
		if (userId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED); // 비회원은 claim 불가
		}

		UUID guestId = PrincipalUtils.guestIdOf(user);
		ChatSessionClaimResponse response = chatSessionService.claimSession(
				sessionId, userId, guestId);

		return ResponseEntity.ok(response);
	}
}
