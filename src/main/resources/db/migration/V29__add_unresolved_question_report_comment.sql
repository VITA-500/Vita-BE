-- (아쉬워요)를 누른 사용자가 남기는 이유. 관리자 미해결 목록에서 함께 본다
ALTER TABLE unresolved_question_reports ADD COLUMN comment VARCHAR(200);
