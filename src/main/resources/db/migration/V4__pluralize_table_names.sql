-- 테이블명 네이밍 일관성 통일 (2026-09-17).
-- users는 PostgreSQL 예약어(user, CURRENT_USER의 동의어)와 충돌을 피하기 위한 의도된 예외로
-- 복수형을 그대로 유지한다. 나머지 테이블은 전부 복수형으로 통일한다.
--
-- 참고: 테이블 RENAME은 FK/인덱스/제약조건을 그대로 따라간다(끊어지지 않음). 다만 BIGSERIAL
-- 컬럼이 내부적으로 물고 있는 시퀀스 이름(예: faq_id_seq)은 자동으로 안 바뀐다 — 동작에는
-- 영향 없고 이름만 예전 그대로라 혼동 소지가 있다는 점만 알아둘 것(굳이 안 바꿔도 무방).
ALTER TABLE user_oauth RENAME TO user_oauths;
ALTER TABLE faq RENAME TO faqs;
ALTER TABLE store RENAME TO stores;
ALTER TABLE chat_session RENAME TO chat_sessions;
ALTER TABLE chat_message RENAME TO chat_messages;
ALTER TABLE chat_message_faq_ref RENAME TO chat_message_faq_refs;
ALTER TABLE store_reservation RENAME TO store_reservations;
