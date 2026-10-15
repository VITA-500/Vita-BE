package com.vita.chat.client;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * FE 연동 테스트용 더미 구현. --spring.profiles.active=dummy 일 때만 동작.
 * Bedrock을 붙이기 전에 FE1이 타이핑 UI를 먼저 확인할 수 있다.
 */
@Component
@Profile("dummy")
public class DummyLlmStreamClient implements LlmStreamClient {

    @Override
    public void stream(String question, String context, String conversationHistory, Consumer<String> onDelta) {
        List<String> chunks = List.of("배송은 보통 ", "2~3일 정도 ", "소요됩니다.");
        for (String chunk : chunks) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            onDelta.accept(chunk);
        }
    }
}