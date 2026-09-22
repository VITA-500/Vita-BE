package com.vita.chat.controller;

import java.util.UUID;

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
import com.vita.common.util.PrincipalUtils;

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
	    
		ChatMessageResponse response = chatMessageService.sendMessage(sessionId,  PrincipalUtils.userIdOf(user), PrincipalUtils.guestIdOf(user), request);
		return ResponseEntity.ok(response);
	}
	
	@GetMapping
	public SessionMessagesResponse getMessages(
			@PathVariable Long sessionId,
			@AuthenticationPrincipal UserPrincipal user
			){
		
		Long userId = PrincipalUtils.userIdOf(user);
		UUID guestId = PrincipalUtils.guestIdOf(user);

		if (userId == null && guestId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		
		SessionMessagesResponse  response = chatMessageService.getMessages(sessionId, userId, guestId);
		
		return response;
	}
}
