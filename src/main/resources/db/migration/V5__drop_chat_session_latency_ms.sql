-- V5__drop_chat_message_latency_ms.sql
-- latency_ms는 API 응답(DTO)에서 계산해서만 보여주고 DB에는 저장하지 않기로 결정.
-- 관리자 페이지에서도 메시지별 latency 조회/필터링을 쓰지 않기로 함(FE와 소통 완료).

ALTER TABLE chat_message
    DROP COLUMN latency_ms;