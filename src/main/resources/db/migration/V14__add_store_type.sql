-- 매장 유형: 통신 매장(PHONE) / 제휴 매장(PARTNER).
-- 제휴 매장도 같은 stores 행이라 상세조회·길찾기가 그대로 동작한다. 기존 매장은 전부 통신 매장이라 기본값은 PHONE.
ALTER TABLE stores
    ADD COLUMN store_type VARCHAR(20) NOT NULL DEFAULT 'PHONE',
    ADD CONSTRAINT chk_store_type CHECK (store_type IN ('PHONE', 'PARTNER'));