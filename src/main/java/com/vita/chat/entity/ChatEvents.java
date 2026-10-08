package com.vita.chat.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import com.vita.chat.dto.StoresPreview;

/**
 * SSE 이벤트 이름과 payload 정의.
 * 모든 payload에는 sessionId, messageId가 들어간다. (event id는 SSE 프로토콜의 id 필드로 별도 전송)
 */
public final class ChatEvents {

    private ChatEvents() {}

    // SSE event name
    public static final String CONNECTED = "connected";
    public static final String ASSISTANT_STATUS = "assistant_status";
    public static final String ASSISTANT_DELTA = "assistant_delta";
    public static final String ASSISTANT_DONE = "assistant_done";
    public static final String ASSISTANT_ERROR = "assistant_error";

    public enum AssistantStatus {
        THINKING("thinking"),
        RETRIEVING_FAQ("retrieving_faq"),
        GENERATING("generating");

        private final String value;

        AssistantStatus(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    public record Status(Long sessionId, Long messageId, AssistantStatus status) {}

    /** delta는 전체 답변이 아니라 "새로 추가된" 텍스트 조각 */
    public record Delta(Long sessionId, Long messageId, String delta) {}

    /** 생성 완료. 최종 content와 구조화 필드(suggestedActions 등)는 여기에 추가 */
    public record Done(Long sessionId, Long messageId, String content, StoresPreview storesPreview) {}

    public record Error(Long sessionId, Long messageId, String code, String message) {}
}