package com.vita.trace;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 질문 1건의 처리 흐름 전체(Trace)의 기록. 저장소에 한 번에 쓰는 단위다.
 *
 * @param messageId  답변 메시지 id(원본과 연결하지 않는 찾기용 값)
 * @param appVersion 이 기록을 만든 앱 버전. 알 수 없으면 null
 * @param config     그 시점의 설정 스냅샷(임계값, 모델, 프롬프트 해시 등)
 * @param startedAt  처리를 시작한 시각(traces.created_at에 저장)
 * @param spans      단계 기록. 실행한 순서대로
 */
public record TraceRecord(Long messageId, String appVersion, Map<String, Object> config, LocalDateTime startedAt,
		List<SpanRecord> spans) {
}
