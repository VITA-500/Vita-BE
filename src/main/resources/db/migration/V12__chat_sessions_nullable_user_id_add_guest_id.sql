-- V12__chat_sessions_nullable_user_id_add_guest_id.sql

ALTER TABLE chat_sessions ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE chat_sessions ADD COLUMN guest_id UUID;

-- user_id 와 guest_id 둘 다 없는 세션 방지
ALTER TABLE chat_sessions
    ADD CONSTRAINT chk_chat_sessions_owner
    CHECK ((user_id IS NULL) != (guest_id IS NULL))

 CREATE INDEX idx_chat_sessions_guest_id ON chat_sessions (guest_id);