package com.vita.chat.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 관리자 대시보드 미해결 질문 통계.
 *
 * @param summary    위쪽 숫자 카드 (미해결 질문 수, 반복 문의 합계, 오늘 발생). reason 필터와 관계없이 전체 기준
 * @param candidates 같은 질문끼리 묶은 미해결 질문 (많이 나온 순)
 */
public record ChatStatsResponse(Summary summary, List<Candidate> candidates) {

    /**
     * @param unresolvedCount   미해결 질문 수 (같은 질문은 하나로)
     * @param repeatCount       미해결 질문이 나온 전체 횟수
     * @param todayCount        오늘 마지막으로 나온 미해결 질문 수
     * @param todayLikeCount    오늘(KST) 전체 답변이 받은 (좋아요) 수. 미해결 목록과 관계없음
     * @param todayDislikeCount 오늘(KST) 전체 답변이 받은 (아쉬워요) 수. 미해결 목록과 관계없음
     * @param reasonCounts      사유별 발생 수 (무시한 것 제외). 네 사유가 항상 들어 있음
     * @param todayReasonCounts 사유별 오늘 발생 수
     */
    public record Summary(long unresolvedCount, long repeatCount, long todayCount,
                          long todayLikeCount, long todayDislikeCount,
                          Map<String, Long> reasonCounts, Map<String, Long> todayReasonCounts) {
    }

    /**
     * @param id             가장 최근에 나온 답변 메시지 id
     * @param answer         가장 최근에 나온 챗봇 답변
     * @param source         CHAT
     * @param failReason     가장 최근 발생의 이유 (NO_MATCH, LOW_SIMILARITY, ANSWER_FAILED, DISLIKED)
     * @param count          같은 질문이 미해결로 나온 횟수
     * @param likeCount      같은 질문의 답변이 받은 (좋아요) 수 (전체 기간)
     * @param dislikeCount   같은 질문의 답변이 받은 (아쉬워요) 수 (전체 기간, 무시한 것 포함)
     * @param comments       (아쉬워요) 이유. 최근 순 최대 5개, 없으면 빈 목록
     */
    public record Candidate(Long id, String question, String answer, String source,
                            String failReason, long count, LocalDateTime lastOccurredAt,
                            long likeCount, long dislikeCount, List<String> comments) {
    }
}