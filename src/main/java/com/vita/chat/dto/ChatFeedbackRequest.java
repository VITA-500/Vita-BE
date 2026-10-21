package com.vita.chat.dto;

import com.vita.chat.ChatMessageFeedback;

/**
 * 답변 피드백 요청
 *
 * @param feedback LIKE, DISLIKE, null(취소)
 * @param comment  (아쉬워요) 이유. DISLIKE일 때만, 0~200자. 보내지 않으면 기존 이유 유지, 빈 문자열이면 지움
 */
public record ChatFeedbackRequest(
        ChatMessageFeedback feedback,
        String comment) { }
