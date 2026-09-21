-- 가상 요금제의 정형 조건 검색과 자연어 의미 검색을 위한 테이블.
-- FAQ와 동일한 intfloat/multilingual-e5-base 모델(vector dimension = 768)을 사용한다.
CREATE TABLE plans (
    id                      BIGSERIAL PRIMARY KEY,
    plan_code               VARCHAR(50) NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    summary                 VARCHAR(255) NOT NULL,

    -- 부가세를 포함한 월정액(원)
    monthly_fee             INT NOT NULL,
    network_type            VARCHAR(20) NOT NULL,
    target_group            VARCHAR(20) NOT NULL,
    min_age                 INT,
    max_age                 INT,

    -- 데이터량은 MB, 소진 후 속도는 Kbps 단위로 저장한다.
    -- 데이터 소진 후 이용이 차단되는 제한형 요금제는 exhausted_speed_kbps를 NULL로 둔다.
    data_policy             VARCHAR(20) NOT NULL,
    base_data_mb            BIGINT,
    exhausted_speed_kbps    INT,

    voice_policy            VARCHAR(20) NOT NULL,
    voice_minutes           INT,
    sms_policy              VARCHAR(20) NOT NULL,
    sms_count               INT,

    -- 임베딩 대상이 되는 요금제 전체 자연어 설명
    description             TEXT NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    embedding               vector(768),
    embedding_model         VARCHAR(100),
    embedding_version       VARCHAR(30),
    embedded_at             TIMESTAMP,

    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP,

    CONSTRAINT uk_plans_plan_code
        UNIQUE (plan_code),
    CONSTRAINT chk_plans_plan_code_not_blank
        CHECK (btrim(plan_code) <> ''),
    CONSTRAINT chk_plans_name_not_blank
        CHECK (btrim(name) <> ''),
    CONSTRAINT chk_plans_summary_not_blank
        CHECK (btrim(summary) <> ''),
    CONSTRAINT chk_plans_description_not_blank
        CHECK (btrim(description) <> ''),
    CONSTRAINT chk_plans_monthly_fee
        CHECK (monthly_fee >= 0),
    CONSTRAINT chk_plans_network_type
        CHECK (network_type IN ('LTE', '5G', 'LTE_5G')),
    CONSTRAINT chk_plans_target_group
        CHECK (target_group IN ('GENERAL', 'YOUTH', 'SENIOR', 'KIDS', 'TABLET', 'WATCH')),
    CONSTRAINT chk_plans_age_range
        CHECK (
            (min_age IS NULL OR min_age BETWEEN 0 AND 150)
            AND (max_age IS NULL OR max_age BETWEEN 0 AND 150)
            AND (min_age IS NULL OR max_age IS NULL OR min_age <= max_age)
        ),
    CONSTRAINT chk_plans_data_policy
        CHECK (data_policy IN ('LIMITED', 'UNLIMITED')),
    CONSTRAINT chk_plans_data_amount
        CHECK (
            (data_policy = 'LIMITED' AND base_data_mb IS NOT NULL AND base_data_mb > 0)
            OR
            (data_policy = 'UNLIMITED' AND base_data_mb IS NULL AND exhausted_speed_kbps IS NULL)
        ),
    CONSTRAINT chk_plans_exhausted_speed
        CHECK (exhausted_speed_kbps IS NULL OR exhausted_speed_kbps > 0),
    CONSTRAINT chk_plans_voice_policy
        CHECK (voice_policy IN ('NONE', 'LIMITED', 'UNLIMITED')),
    CONSTRAINT chk_plans_voice_amount
        CHECK (
            (voice_policy = 'LIMITED' AND voice_minutes IS NOT NULL AND voice_minutes > 0)
            OR
            (voice_policy IN ('NONE', 'UNLIMITED') AND voice_minutes IS NULL)
        ),
    CONSTRAINT chk_plans_sms_policy
        CHECK (sms_policy IN ('NONE', 'LIMITED', 'UNLIMITED')),
    CONSTRAINT chk_plans_sms_amount
        CHECK (
            (sms_policy = 'LIMITED' AND sms_count IS NOT NULL AND sms_count > 0)
            OR
            (sms_policy IN ('NONE', 'UNLIMITED') AND sms_count IS NULL)
        ),
    CONSTRAINT chk_plans_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_plans_status
    ON plans(status);

CREATE INDEX idx_plans_monthly_fee
    ON plans(monthly_fee);

CREATE INDEX idx_plans_target_group
    ON plans(target_group);

-- 초기 데이터 규모가 10~20건이므로 별도 HNSW/IVFFlat 인덱스 없이 Exact Search를 사용한다.
