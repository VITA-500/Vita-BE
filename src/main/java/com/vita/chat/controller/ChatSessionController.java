package com.vita.chat.controller;

import java.util.UUID;

import com.vita.chat.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.vita.auth.security.UserPrincipal;
import com.vita.chat.service.ChatSessionService;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.util.PrincipalUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/chat/sessions")
@RequiredArgsConstructor
@Slf4j
public class ChatSessionController {

	private final ChatSessionService chatSessionService;
	
	@PostMapping
	public ResponseEntity<ChatSessionCreateResponse> createSession(
			@AuthenticationPrincipal UserPrincipal user
			){
		
		Long userId = PrincipalUtils.userIdOf(user);
		UUID guestId = PrincipalUtils.guestIdOf(user);
		
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
		
		Long userId = PrincipalUtils.userIdOf(user);
		UUID guestId = PrincipalUtils.guestIdOf(user);
		
		if(userId == null  && guestId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		
		ChatSessionListResponse response = chatSessionService.getSessions(userId);
		
		return response;
	}
	
	@PostMapping("/{sessionId}/claim")
	public ResponseEntity<ChatSessionClaimResponse> claimSession(
			@PathVariable Long sessionId,
			@AuthenticationPrincipal UserPrincipal user,
			@RequestHeader(value = "X-Guest-Id", required = false) UUID guestId) {
		
		Long userId = PrincipalUtils.userIdOf(user);
		if (userId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED); // 비회원은 claim 불가
		}

		ChatSessionClaimResponse response = chatSessionService.claimSession(
				sessionId, userId, guestId);

		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/{sessionId}")
	public ResponseEntity<Void> deleteSession(
			@PathVariable Long sessionId,
			@AuthenticationPrincipal UserPrincipal user){

		Long userId = PrincipalUtils.userIdOf(user);
		if(userId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}

		chatSessionService.deleteSession(sessionId, userId);
		return ResponseEntity.noContent().build();
	}

	@PatchMapping("/{sessionId}/pin")
	public ChatSessionPinResponse pinSession(
			@PathVariable Long sessionId,
			@AuthenticationPrincipal UserPrincipal user,
			@Valid @RequestBody ChatSessionPinRequest request) {

		Long userId = PrincipalUtils.userIdOf(user);
		if (userId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED); // 고정은 회원만
		}

		return chatSessionService.pinSession(sessionId, userId, request.pinned());
	}

}
