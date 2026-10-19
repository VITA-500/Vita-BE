package com.vita.chat.client;

import java.util.function.Consumer;

import com.vita.chat.dto.StreamResult;

/**
 * LLM 스트리밍 호출 추상화.
 * 기존 BedrockChatClient.ask(question, context, conversationHistory)와 같은 입력을 받는다.
 * onDelta에는 사용자에게 보여줄 답변 텍스트 조각만 넘긴다.
 */
public interface LlmStreamClient {

    /**
     * 스트림이 끝나면 호출 기록(보낸 프롬프트, 토큰 사용량)을 돌려준다. 이 기록은 trace 저장용이라 구현체가
     * 알 수 없는 값은 null로 두면 되고, 기록 때문에 답변 동작이 달라지면 안 된다.
     */
    StreamResult stream(String question, String context, String conversationHistory, Consumer<String> onDelta);
}
 