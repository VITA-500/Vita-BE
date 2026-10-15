package com.vita.chat.client;

import java.util.function.Consumer;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
 
 
import lombok.RequiredArgsConstructor;
 
/** 실제 Bedrock(Spring AI) 스트리밍 구현체. dummy 프로파일이 아닐 때 사용. */
@Component
@Profile("!dummy")
@RequiredArgsConstructor
public class BedrockLlmStreamClient implements LlmStreamClient {
 
    private final BedrockChatClient bedrockChatClient;
 
    @Override
    public void stream(String question, String context, String conversationHistory, Consumer<String> onDelta) {
        bedrockChatClient.askStream(question, context, conversationHistory, onDelta);
    }
}
 