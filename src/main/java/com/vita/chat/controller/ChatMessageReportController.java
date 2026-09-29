package com.vita.chat.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vita.auth.security.UserPrincipal;
import com.vita.chat.dto.ReportResponse;
import com.vita.chat.service.ChatReportService;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.util.PrincipalUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/chat/messages/{messageId}")
@RequiredArgsConstructor
public class ChatMessageReportController {

	private final ChatReportService chatReportService;

	@PostMapping("/report-to-admin")
	public ReportResponse reportToAdmin(
			@PathVariable Long messageId,
			@AuthenticationPrincipal UserPrincipal user
			) {

		Long userId = PrincipalUtils.userIdOf(user);
		if (userId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}

		return chatReportService.report(messageId, userId);
	}
}