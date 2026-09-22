package com.vita.chat.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vita.auth.security.UserPrincipal;
import com.vita.chat.dto.ChatMessageResponse;
import com.vita.chat.dto.ChatMessageSendRequest;
import com.vita.chat.dto.ChatSessionListResponse;
import com.vita.chat.dto.SessionMessagesResponse;
import com.vita.chat.service.ChatMessageService;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/chat/sessions/{sessionId}/messages")
@RequiredArgsConstructor
public class ChatMessageController {

	private final ChatMessageService chatMessageService;
	
	@PostMapping
	public ResponseEntity<ChatMessageResponse> sendMessage( 
			@PathVariable Long sessionId,
			@AuthenticationPrincipal UserPrincipal user,
			@Valid @RequestBody ChatMessageSendRequest request
			)
	{
		ChatMessageResponse response = chatMessageService.sendMessage(sessionId,  user.getUserId(), user.getGuestId(), request);
		return ResponseEntity.ok(response);
	}
	
	@GetMapping
	public SessionMessagesResponse getMessages(
			@PathVariable Long sessionId,
			@AuthenticationPrincipal UserPrincipal user
			){
		
		Long userId = user.getUserId();
		Long guestId = user.getGeustId();
		
		if (userId == null && guestId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		
		SessionMessagesResponse  response = chatMessageService.getMessages(sessionId, userId, guestId);
		
		return response;
	}
}
