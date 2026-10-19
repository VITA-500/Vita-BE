package com.vita.trace;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 끝난 단계 하나의 기록. 저장 직전에 {@link TraceCollector}가 만든다.
 *
 * @param type      단계 종류
 * @param startedAt 단계 시작 시각
 * @param endedAt   단계 종료 시각
 * @param history   그 단계의 원문 스냅샷. 오류가 났다면 {@code error} 필드를 가진다
 */
public record SpanRecord(SpanType type, LocalDateTime startedAt, LocalDateTime endedAt, Map<String, Object> history) {
}
