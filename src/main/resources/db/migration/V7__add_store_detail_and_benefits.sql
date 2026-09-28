-- 매장 상세정보 컬럼 + 제휴 혜택 테이블 (2026-09-20 팀 회의 반영, 06_ERD.dbml 기준).
-- 제휴 혜택 여부(hasBenefit)는 stores 컬럼으로 두지 않고 store_benefits에 행이 있는지로 계산한다.

-- 1. stores 상세정보 (기존 매장 행은 빈 배열 — 시드/관리자 수정으로 채운다)
ALTER TABLE stores
    ADD COLUMN consult_services  TEXT[] NOT NULL DEFAULT '{}',   -- 상담 가능 업무 (휴대폰상담, 요금제변경, 인터넷상담, 요금수납 등)
    ADD COLUMN provided_services TEXT[] NOT NULL DEFAULT '{}';   -- 제공 가능 서비스

-- 2. benefits (제휴 혜택)
CREATE TABLE benefits (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,            -- 혜택명 (예: 아메리카노 20% 할인)
    category    VARCHAR(30)  NOT NULL,            -- 카페/편의점/문화 등 (enum 강제 안 함)
    description TEXT,
    updated_by  BIGINT REFERENCES users(id),      -- 마지막으로 수정한 관리자
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP
);

CREATE INDEX idx_benefit_category ON benefits(category);

-- 3. store_benefits (매장-혜택 N:M)
-- 관리자가 매장/혜택을 삭제할 때 매핑 행 때문에 FK 위반이 나지 않도록 ON DELETE CASCADE를 둔다.
CREATE TABLE store_benefits (
    id         BIGSERIAL PRIMARY KEY,
    store_id   BIGINT NOT NULL REFERENCES stores(id)   ON DELETE CASCADE,
    benefit_id BIGINT NOT NULL REFERENCES benefits(id) ON DELETE CASCADE,
    CONSTRAINT uk_store_benefit UNIQUE (store_id, benefit_id)
);

CREATE INDEX idx_store_benefit_benefit ON store_benefits(benefit_id);