-- 매장 영업시간을 여는 시각·닫는 시각으로 분리 (2026-10-06).
-- "6시에 연 매장", "지금 영업 중인 매장"처럼 영업시간을 조회 조건으로 쓰려면 글자("10:00-20:00")가 아니라
-- 시각으로 저장되어 있어야 한다. 글자를 쿼리에서 잘라 쓰면 형식이 깨진 값 하나에 쿼리 전체가 실패한다.
-- 닫는 시각이 여는 시각보다 이르면 자정을 넘겨 다음 날까지 영업하는 것으로 본다 (예: 영화관 09:00-02:00).
-- 기존 business_hours 칸은 코드에서 더 이상 쓰지 않으며, 이후 별도 마이그레이션에서 삭제한다.

ALTER TABLE stores
    ADD COLUMN open_time  TIME,
    ADD COLUMN close_time TIME;

-- 기존 "HH:mm-HH:mm" 값만 변환한다. 형식이 맞지 않는 값은 비워 둔다 (영업시간 미등록과 같은 취급).
UPDATE stores
SET open_time  = split_part(business_hours, '-', 1)::TIME,
    close_time = split_part(business_hours, '-', 2)::TIME
WHERE business_hours ~ '^([01][0-9]|2[0-3]):[0-5][0-9]-([01][0-9]|2[0-3]):[0-5][0-9]$'
  AND split_part(business_hours, '-', 1) <> split_part(business_hours, '-', 2);

-- 둘 다 있거나 둘 다 없어야 한다. 여는 시각과 닫는 시각이 같은 값은 허용하지 않는다.
ALTER TABLE stores
    ADD CONSTRAINT chk_store_business_hours
        CHECK ((open_time IS NULL AND close_time IS NULL)
            OR (open_time IS NOT NULL AND close_time IS NOT NULL AND open_time <> close_time));