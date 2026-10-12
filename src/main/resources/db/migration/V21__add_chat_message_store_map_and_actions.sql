-- 채팅 답변에 함께 보내는 매장 지도(storeMap)와 추천 버튼(actions)을 저장한다.
-- 상담 기록을 다시 열었을 때(새로고침, 다른 기기) 지도와 버튼을 되살리기 위함.
-- 매장 질문이 아니거나 버튼이 없으면 NULL.
ALTER TABLE chat_messages
    ADD COLUMN store_map JSONB,
    ADD COLUMN actions   JSONB;