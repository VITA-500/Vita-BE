-- 정제 FAQ JSONL과 DB 행을 연결해 재실행 가능한 upsert를 지원한다.
-- 한 정책에서 여러 FAQ가 만들어지므로 정책 ID 자체를 unique key로 사용하지 않는다.
ALTER TABLE faq
    ADD COLUMN source_faq_id VARCHAR(100),
    ADD COLUMN source_policy_ids TEXT[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT uk_faq_source_faq_id UNIQUE (source_faq_id);
