package com.vita.chat.service;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.vita.chat.entity.ChatEvents;

import lombok.extern.slf4j.Slf4j;

/**
 * sessionId별로 SSE 연결(SseEmitter)을 관리하고 이벤트를 발행한다.
 * 인스턴스가 1대라는 전제의 인메모리 구현. 여러 대로 늘리면 Redis pub/sub 등이 필요하다.
 */
@Slf4j
@Component
public class ChatSseRegistry {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // 30분, 끊기면 FE가 재연결

    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    // 세션 단위로 증가하는 event id (재연결 시 중복 제거용)
    private final Map<Long, AtomicLong> sequences = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long sessionId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(emitter);

        Runnable cleanup = () -> remove(sessionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 연결 직후 한 번 보내서 FE가 연결 성공을 알 수 있게 하고, 프록시 버퍼링도 깨운다
        try {
            emitter.send(SseEmitter.event().name(ChatEvents.CONNECTED).data("ok"));
        } catch (IOException e) {
            cleanup.run();
        }
        return emitter;
    }

    public void publish(Long sessionId, String eventName, Object payload) {
        Set<SseEmitter> targets = emitters.get(sessionId);
        if (targets == null || targets.isEmpty()) {
            return; // 구독자가 없으면 버림. 최종 답변은 DB에 저장되므로 FE가 재조회로 복구
        }
        long eventId = sequences.computeIfAbsent(sessionId, k -> new AtomicLong()).incrementAndGet();

        for (SseEmitter emitter : targets) {
            try {
                // 여러 스레드가 같은 emitter에 동시에 쓰지 않도록 동기화
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(eventId))
                            .name(eventName)
                            .data(payload, MediaType.APPLICATION_JSON));
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE send 실패, 연결 제거. sessionId={}", sessionId);
                remove(sessionId, emitter);
            }
        }
    }

    /** 프록시/로드밸런서의 idle timeout 방지용 heartbeat (comment 라인이라 FE 이벤트로는 안 보임) */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        emitters.forEach((sessionId, targets) -> {
            for (SseEmitter emitter : targets) {
                try {
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    }
                } catch (IOException | IllegalStateException e) {
                    remove(sessionId, emitter);
                }
            }
        });
    }

    private void remove(Long sessionId, SseEmitter emitter) {
        Set<SseEmitter> targets = emitters.get(sessionId);
        if (targets == null) {
            return;
        }
        targets.remove(emitter);
        if (targets.isEmpty()) {
            emitters.remove(sessionId, targets);
        }
    }
}