package com.vita.chat.dto;

/**
 * 관리자 미해결 질문 무시 결과
 *
 * @param id             무시한 항목 id (목록의 candidates[].id)
 * @param dismissedCount 목록에서 내린 답변 수. 자동 감지와 (아쉬워요)를 합친 수로 항목의 count와 같음
 */
public record ChatDismissResponse(Long id, int dismissedCount) {
}