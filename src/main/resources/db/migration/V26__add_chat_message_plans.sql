-- 답변과 함께 보낸 요금제 카드. 답변 시점 값을 그대로 저장해 과거 대화를 다시 열어도 당시 카드로 보인다
ALTER TABLE chat_messages ADD COLUMN plans jsonb;
