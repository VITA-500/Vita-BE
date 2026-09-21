package com.vita.chat.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vita.chat.dto.ChatMessageResponse;
import com.vita.chat.dto.ChatMessageSendRequest;
import com.vita.chat.service.ChatMessageService;

import io.swagger.v3.oas.annotations.parameters.RequestBody;
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
			@Valid @RequestBody ChatMessageSendRequest request
			)
	{
		ChatMessageResponse response = chatMessageService.sendMessage(sessionId, request);
		return ResponseEntity.ok(response);
	}
}
