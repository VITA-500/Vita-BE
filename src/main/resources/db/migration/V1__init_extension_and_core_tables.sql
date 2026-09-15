-- 06_ERD.dbml 기준 확정 스키마 (2026-09-15). 소셜 로그인(user_oauth) 포함해 ERD보다 확장된 버전 —
-- 06_ERD.dbml도 이 파일 기준으로 갱신 필요.

-- 0. pgvector 확장
CREATE EXTENSION IF NOT EXISTS vector;

-- 1. ENUM 타입
CREATE TYPE user_role AS ENUM ('USER', 'ADMIN');
CREATE TYPE auth_provider AS ENUM ('LOCAL', 'GOOGLE', 'KAKAO', 'NAVER');
CREATE TYPE chat_message_role AS ENUM ('user', 'assistant');
CREATE TYPE chat_message_status AS ENUM ('PENDING', 'COMPLETED', 'FAILED', 'RETRYING');
CREATE TYPE feedback_type AS ENUM ('like', 'dislike');

-- 2. users
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255),               -- NULL 허용: 카카오는 이메일 미제공 가능
    password_hash   VARCHAR(255),               -- NULL 허용: 소셜 전용 계정엔 없음
    name            VARCHAR(100),
    phone           VARCHAR(20),
    role            user_role NOT NULL DEFAULT 'USER',
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP
);

-- 이메일은 "있을 때만" 유일해야 함 (NULL끼리는 중복 허용)
CREATE UNIQUE INDEX uk_users_email
    ON users (email)
    WHERE email IS NOT NULL;

-- 소셜 연동 (1:N — 한 사람이 여러 소셜 계정 연결 가능)
CREATE TABLE user_oauth (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider        auth_provider NOT NULL,
    provider_id     VARCHAR(255) NOT NULL,

    UNIQUE (provider, provider_id),   -- 로그인 식별 키
    UNIQUE (user_id, provider)        -- 같은 소셜을 두 번 연결 못하게
);

-- 3. faq
-- multilingual-e5-base 기준 Embedding Dimension = 768, 검색은 cosine distance(<=>) 기준 사용.
-- 향후 BGE-M3(1024)로 교체할 경우 embedding 컬럼 dimension 변경 및 전체 FAQ Re-Embedding 필요 —
-- 기존 벡터와 새 모델의 벡터는 같은 공간에 있지 않으므로 섞어 쓸 수 없다.
-- 삭제는 하드 delete가 아니라 status(ACTIVE/INACTIVE)로 처리 — FR-M03/04_API명세서 5.4절 문서도
-- 이에 맞춰 갱신 필요(과거엔 "row 삭제로 임베딩도 자동 삭제"를 전제로 쓰여 있었음).
CREATE TABLE faq (
    id                  BIGSERIAL PRIMARY KEY,
    category            VARCHAR(50) NOT NULL,
    subcategory         VARCHAR(50),
    question            TEXT NOT NULL,
    answer              TEXT NOT NULL,

    -- FAQ를 실제 삭제하지 않고 검색 대상에서 제외하기 위한 상태값
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    -- multilingual-e5-base 출력 dimension = 768
    embedding           vector(768),

    -- 현재 FAQ Vector가 어떤 모델로 생성되었는지 추적
    embedding_model     VARCHAR(100),

    -- Embedding 정책 버전
    -- 예: v1 = question만 embedding
    --     v2 = question + answer embedding
    embedding_version   VARCHAR(30),

    -- 마지막 Embedding 생성 시각
    embedded_at         TIMESTAMP,

    updated_by          BIGINT REFERENCES users(id),

    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP,

    CONSTRAINT chk_faq_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_faq_category
    ON faq(category);

CREATE INDEX idx_faq_status
    ON faq(status);

-- 초기 FAQ 규모가 약 1,000건이므로
-- V1에서는 IVFFlat/HNSW 인덱스를 생성하지 않고 Exact Search 사용
-- 데이터 증가 또는 성능 문제가 확인되면 Vector Index 추가 검토

-- 4. store
CREATE TABLE store (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    address         VARCHAR(255) NOT NULL,
    lat             DECIMAL(9,6) NOT NULL,
    lng             DECIMAL(9,6) NOT NULL,
    business_hours  VARCHAR(100),
    phone           VARCHAR(20),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP
);

CREATE INDEX idx_store_location ON store(lat, lng);

-- 5. chat_session
CREATE TABLE chat_session (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    title           VARCHAR(200),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP
);

CREATE INDEX idx_chat_session_user ON chat_session(user_id);

-- 6. chat_message
CREATE TABLE chat_message (
    id              BIGSERIAL PRIMARY KEY,
    session_id      BIGINT NOT NULL REFERENCES chat_session(id),
    role            chat_message_role NOT NULL,
    content         TEXT NOT NULL,
    status          chat_message_status,
    feedback        feedback_type,
    latency_ms      INT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_message_session ON chat_message(session_id);

-- 7. chat_message_faq_ref (N:M)
CREATE TABLE chat_message_faq_ref (
    id              BIGSERIAL PRIMARY KEY,
    chat_message_id BIGINT NOT NULL REFERENCES chat_message(id),
    faq_id          BIGINT NOT NULL REFERENCES faq(id),
    CONSTRAINT uk_message_faq UNIQUE (chat_message_id, faq_id)
);

CREATE INDEX idx_message_faq_ref_faq ON chat_message_faq_ref(faq_id);

-- 8. store_reservation (선택, 추후 확장)
CREATE TABLE store_reservation (
    id                  BIGSERIAL PRIMARY KEY,
    store_id            BIGINT NOT NULL REFERENCES store(id),
    user_id             BIGINT NOT NULL REFERENCES users(id),
    reservation_date    DATE NOT NULL,
    reservation_time    TIME NOT NULL,
    status              VARCHAR(20) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_reservation_store ON store_reservation(store_id);
CREATE INDEX idx_reservation_user ON store_reservation(user_id);
