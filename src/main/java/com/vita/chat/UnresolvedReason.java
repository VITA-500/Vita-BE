package com.vita.chat;

/**
 * 답변이 질문을 해결하지 못한 이유
 */
public enum UnresolvedReason {
    NO_MATCH,           // FAQ 및 요금제 검색 결과 없음
    LOW_SIMILARITY,     // 비슷한 FAQ는 있었지만 기준 유사도 미달
    ANSWER_FAILED,      // 답변 생성 실패
    DISLIKED;           // 사용자가 (아쉬워요) 누름

    /**
     * 이 이상이면 "비슷한 FAQ가 있었다"(LOW_SIMILARITY)
     * 아래면 "검색 결과 없음" (NO_MATCH)
     */
    static final double LOW_SIMILARITY_FLOOR = 0.80;

    /**
     * 답변이 끝났을 때 미해결 여부 판단.
     * @param storeQuestion     매장 및 제휴 질문
     * @param hasContext        FAQ, 요금제, 극값 조회 결과가 답변 재료로 들어갔는지
     * @param topSimilarity     검색 최고 유사도
     * @return
     */
    public static UnresolvedReason judge(boolean storeQuestion, boolean hasContext, Double topSimilarity){
        if(storeQuestion || hasContext){
            return null;
        }
        if(topSimilarity != null && topSimilarity >= LOW_SIMILARITY_FLOOR){
            return LOW_SIMILARITY;
        }
        return NO_MATCH;
    }
}
