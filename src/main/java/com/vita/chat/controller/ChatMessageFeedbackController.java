package com.vita.chat.controller;

import com.vita.auth.security.UserPrincipal;
import com.vita.chat.dto.ChatFeedbackRequest;
import com.vita.chat.dto.ChatFeedbackResponse;
import com.vita.chat.service.ChatMessageService;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.util.PrincipalUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/chat/messages/{messageId}")
@RequiredArgsConstructor
public class ChatMessageFeedbackController {

    private final ChatMessageService chatMessageService;

    @PostMapping("/feedback")
    public ChatFeedbackResponse feedback(
            @PathVariable Long messageId,
            @AuthenticationPrincipal UserPrincipal user,
            @RequestBody ChatFeedbackRequest request) {
        Long userId = PrincipalUtils.userIdOf(user);
        UUID guestId = PrincipalUtils.guestIdOf(user);

        if(userId == null && guestId == null){
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return chatMessageService.applyFeedback(messageId, userId, guestId, request.feedback());
    }
}
