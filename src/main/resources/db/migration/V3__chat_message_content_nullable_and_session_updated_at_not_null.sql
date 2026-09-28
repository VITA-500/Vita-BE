-- V2: chat_message.content Nullable 전환 + chat_session.updated_at NOT NULL 전환 (2026-09-15)
-- V1(v1__init_extension_and_core_tables.sql) 기준 스키마에 이어지는 변경분.
-- 06_ERD.dbml에도 동일하게 반영 필요.

-- 1. chat_message.content: NOT NULL -> Nullable
-- 사유: 명세서 3.4 실패 케이스가 {"status":"FAILED", "answer":null, "retryable":true}로 내려가야 함.
-- 질문 접수 시점에 PENDING 상태로 미리 row를 만들어두고(Phase 2 SSE/재시도 대비),
-- LLM 응답 생성이 실패하면 content가 채워지지 않은 채 FAILED로 남는 케이스가 있어 NN 제약을 풀어야 함.
ALTER TABLE chat_message
    ALTER COLUMN content DROP NOT NULL;

-- 2. chat_session.updated_at: Nullable -> NOT NULL
-- 사유: 세션 목록 조회(GET /chat/sessions)가 updated_at 기준 정렬을 전제로 함.
-- 세션 생성 시 created_at과 동일 값으로 채워지므로 실질적으로 항상 값이 존재 — NN으로 맞춤.
-- 기존에 이미 NULL로 들어간 row가 있을 수 있으므로 제약 추가 전에 먼저 백필.
UPDATE chat_session
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE chat_session
    ALTER COLUMN updated_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET NOT NULL;