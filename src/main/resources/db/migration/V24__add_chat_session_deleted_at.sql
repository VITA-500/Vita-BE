-- 사용자가 지운 대화. 관리자 신고 목록과 미해결 통계가 메시지를 참조해서 행은 남기고 지운 시각만 기록한다
ALTER TABLE chat_sessions ADD COLUMN deleted_at TIMESTAMP;