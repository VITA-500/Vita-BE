package com.vita.chat.dto;

import com.vita.chat.ChatMessageFeedback;

/** 답변 피드백 요청 */
public record ChatFeedbackRequest(
        ChatMessageFeedback feedback) { }
