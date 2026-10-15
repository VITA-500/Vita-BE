package com.vita.chat.controller;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.vita.auth.security.UserPrincipal;
import com.vita.chat.service.ChatMessageService;
import com.vita.chat.service.ChatSseRegistry;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.util.PrincipalUtils;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/chat/sessions")
@RequiredArgsConstructor
public class ChatEventController {

    private final ChatSseRegistry registry;
    private final ChatMessageService chatMessageService;
    
    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal UserPrincipal user,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {

    	Long userId = PrincipalUtils.userIdOf(user);
    	UUID guestId = PrincipalUtils.guestIdOf(user);

    	if (userId == null && guestId == null) {
    	    throw new BusinessException(ErrorCode.UNAUTHORIZED);
    	}
    	
    	// 2) 세션 존재 여부와 소유자 검증 (getOwnedSession과 동일한 규칙)
    	chatMessageService.getOwnedSession(sessionId, userId, guestId);
        
        // nginx 등 프록시가 응답을 모아서 보내지 않도록 버퍼링 해제
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        // lastEventId는 1차에서는 사용하지 않음. 재연결 시 FE가 GET /messages로 재조회.
        // 이벤트 버퍼를 도입하면 여기서 lastEventId 이후 이벤트를 재전송한다.
        return registry.subscribe(sessionId);
    }
}