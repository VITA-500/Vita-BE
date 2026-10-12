-- 답변이 질문을 해결하지 못한 이유(미해결 질문 자동 감지)와 검색 최고 유사도를 저장한다.
-- 관리자 대시보드 "미해결 질문 큐"(GET /admin/stats/chat)에서 사용.
--   NO_MATCH       : FAQ·요금제 검색 결과 없음
--   LOW_SIMILARITY : 비슷한 FAQ는 있었지만 기준 유사도 미달
--   ANSWER_FAILED  : 답변 생성 실패
-- 해결된 답변, 매장 질문은 NULL.
ALTER TABLE chat_messages
    ADD COLUMN unresolved_reason VARCHAR(30),
    ADD COLUMN top_similarity    DOUBLE PRECISION;

CREATE INDEX idx_chat_messages_unresolved ON chat_messages (unresolved_reason)
    WHERE unresolved_reason IS NOT NULL;