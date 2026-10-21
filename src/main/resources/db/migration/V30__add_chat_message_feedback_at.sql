-- 피드백을 누른 시각. 관리자 대시보드 "오늘 (좋아요)/(아쉬워요)" 집계에 쓴다
ALTER TABLE chat_messages ADD COLUMN feedback_at TIMESTAMP;

-- 이미 남은 피드백은 누른 시각을 모르므로 답변 시각으로 채운다
UPDATE chat_messages SET feedback_at = created_at WHERE feedback IS NOT NULL;
