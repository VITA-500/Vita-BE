-- 관리자 "무시": 자동 감지된 미해결 답변도 목록에서 내릴 수 있게 무시한 시각을 남긴다
ALTER TABLE chat_messages ADD COLUMN unresolved_dismissed_at TIMESTAMP;
