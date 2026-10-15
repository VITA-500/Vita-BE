-- 대화 고정. 목록에서 고정한 대화를 먼저 보여 준다
ALTER TABLE chat_sessions ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;