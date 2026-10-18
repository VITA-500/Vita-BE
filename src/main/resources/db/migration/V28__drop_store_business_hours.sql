-- V19에서 영업시간을 open_time, close_time 두 칸으로 나눈 뒤 쓰지 않는 기존 칸을 지운다
ALTER TABLE stores DROP COLUMN business_hours;
