-- 제휴 브랜드 혜택 시드 + 제휴 매장 연결 (2026-10-01).
-- 1브랜드 = 1혜택으로 정했으므로 benefits에 brand를 두고 UNIQUE로 보장한다.
-- 브랜드명은 제휴 매장 이름의 앞부분과 같아서("스노우콘 강남점" → 스노우콘) 매장 연결 기준으로도 쓴다.
-- 업종(category)은 혜택에만 두고, 매장의 업종은 store_benefits로 따라가서 구한다 (단일 출처).

-- 1. brand 컬럼 (기존 행이 있으면 name으로 채운 뒤 NOT NULL)
ALTER TABLE benefits ADD COLUMN brand VARCHAR(50);
UPDATE benefits SET brand = name WHERE brand IS NULL;
ALTER TABLE benefits ALTER COLUMN brand SET NOT NULL;
ALTER TABLE benefits ADD CONSTRAINT uk_benefit_brand UNIQUE (brand);

-- 2. 브랜드별 혜택 (카테고리: 카페 / 아이스크림 / 영화 / 외식 / 자동차 / 쇼핑 / 여가)
INSERT INTO benefits (brand, name, category, description) VALUES
  ('루나빈',       '아메리카노 20% 할인',     '카페',       'VITA 멤버십 회원 대상, 매장 방문 시 1일 1회. 다른 할인과 중복 불가.'),
  ('모닝브루',     '음료 사이즈 업',          '카페',       'VITA 멤버십 회원 대상, 제조 음료 1잔 사이즈 업. 1일 1회.'),
  ('스노우콘',     '싱글레귤러 1+1',          '아이스크림', 'VITA 멤버십 회원 대상, 싱글레귤러 구매 시 1개 추가 증정. 월 1회.'),
  ('씨네온',       '영화 예매 2,000원 할인',  '영화',       'VITA 멤버십 회원 대상, 2D 일반 영화 예매 시 1인 2,000원 할인. 월 2회.'),
  ('그릴하우스',   '식사 금액 15% 할인',      '외식',       'VITA 멤버십 회원 대상, 동반 4인까지 식사 금액 15% 할인. 주류 제외.'),
  ('라이드카',     '렌터카 대여료 30% 할인',  '자동차',     'VITA 멤버십 회원 대상, 단기 렌터카 대여료 30% 할인. 보험료 제외.'),
  ('데일리샵',     '구매 금액 10% 할인',      '쇼핑',       'VITA 멤버십 회원 대상, 1만 원 이상 구매 시 10% 할인. 1일 1회.'),
  ('어진월드',     '종합이용권 40% 할인',     '여가',       'VITA 멤버십 회원 대상, 본인 포함 4인까지 종합이용권 40% 할인.'),
  ('어진스카이',   '입장권 30% 할인',         '여가',       'VITA 멤버십 회원 대상, 본인 포함 2인까지 전망대 입장권 30% 할인.'),
  ('엔디랜드',     '자유이용권 40% 할인',     '여가',       'VITA 멤버십 회원 대상, 본인 포함 4인까지 자유이용권 40% 할인.'),
  ('캐리비안제홍', '입장권 35% 할인',         '여가',       'VITA 멤버십 회원 대상, 본인 포함 4인까지 입장권 35% 할인.')
    ON CONFLICT (brand) DO NOTHING;

-- 3. 제휴 매장 ↔ 혜택 연결: 매장 이름이 브랜드명과 같거나 "브랜드명 + 공백"으로 시작하면 연결.
--    매장 데이터가 이 마이그레이션보다 늦게 들어오는 환경을 위해 제휴 매장 정리 SQL에도 같은 구문이 있다.
INSERT INTO store_benefits (store_id, benefit_id)
SELECT s.id, b.id
FROM stores s
         JOIN benefits b ON s.name = b.brand OR s.name LIKE b.brand || ' %'
WHERE s.store_type = 'PARTNER'
    ON CONFLICT (store_id, benefit_id) DO NOTHING;