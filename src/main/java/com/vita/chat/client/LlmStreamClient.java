package com.vita.chat.client;

import java.util.function.Consumer;

/**
 * LLM 스트리밍 호출 추상화.
 * 기존 BedrockChatClient.ask(question, context, conversationHistory)와 같은 입력을 받는다.
 * onDelta에는 사용자에게 보여줄 답변 텍스트 조각만 넘긴다.
 */
public interface LlmStreamClient {
 
    void stream(String question, String context, String conversationHistory, Consumer<String> onDelta);
}
 