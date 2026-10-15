package com.vita.chat.dto;

/**
 * 관리자 미해결 질문 무시 결과
 *
 * @param id             무시한 항목 id (목록의 candidates[].id)
 * @param dismissedCount 무시 처리된 (싫어요) 수
 */
public record ChatDismissResponse(Long id, int dismissedCount) {
}