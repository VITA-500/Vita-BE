package com.vita.chat.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vita.auth.security.UserPrincipal;
import com.vita.chat.dto.ChatSessionCreateResponse;
import com.vita.chat.service.ChatSessionService;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/chat/sessions")
@RequiredArgsConstructor
public class ChatSessionController {

	private final ChatSessionService chatSessionService;
	
	@PostMapping
	public ResponseEntity<ChatSessionCreateResponse> createSession(
			@AuthenticationPrincipal UserPrincipal user){
		
		if (user == null) {
	        throw new BusinessException(ErrorCode.UNAUTHORIZED);
	    }
		
		ChatSessionCreateResponse response = chatSessionService.createSession(user.getUserId());
		
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
