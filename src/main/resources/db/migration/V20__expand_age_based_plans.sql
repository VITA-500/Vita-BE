-- 연령대별 요금제 구간을 KIDS(0~13), TEEN(14~18), YOUTH(19~34), SENIOR(65+)로 구분한다.
ALTER TABLE plans
    DROP CONSTRAINT chk_plans_target_group;

ALTER TABLE plans
    ADD CONSTRAINT chk_plans_target_group
        CHECK (target_group IN ('GENERAL', 'TEEN', 'YOUTH', 'SENIOR', 'KIDS', 'TABLET', 'WATCH'));

-- 키즈 요금제는 초등학생 연령대인 만 13세 이하를 대상으로 조정한다.
UPDATE plans
SET summary = '만 13세 이하 어린이의 기본 연락과 안전한 데이터 이용을 위한 요금제',
    max_age = 13,
    description = '비타 키즈 5는 만 13세 이하 사용자를 위한 월 19,000원의 어린이용 LTE·5G 모바일 요금제입니다. 매월 데이터 5GB와 음성통화 100분, 문자 100건을 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 400Kbps 속도로 계속 이용할 수 있습니다. 보호자와의 연락과 학습 콘텐츠 이용이 필요한 어린이에게 적합합니다.',
    embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedded_at = NULL,
    updated_at = now()
WHERE plan_code = 'VITA-KIDS-5';

INSERT INTO plans (
    plan_code, name, summary, monthly_fee, network_type, target_group,
    min_age, max_age, data_policy, base_data_mb, exhausted_speed_kbps,
    voice_policy, voice_minutes, sms_policy, sms_count, description, status
) VALUES
(
    'VITA-TEEN-10', '비타 틴 10', '청소년의 기본적인 연락과 가벼운 데이터 이용을 위한 요금제',
    24000, 'LTE_5G', 'TEEN', 14, 18, 'LIMITED', 10240, 1000,
    'UNLIMITED', NULL, 'UNLIMITED', NULL,
    '비타 틴 10은 만 14세부터 18세까지 가입할 수 있는 월 24,000원의 청소년용 LTE·5G 모바일 요금제입니다. 매월 데이터 10GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 1Mbps 속도로 계속 이용할 수 있습니다. 메신저, 웹 검색과 가벼운 학습 콘텐츠를 주로 이용하는 청소년에게 적합합니다.',
    'ACTIVE'
),
(
    'VITA-TEEN-30', '비타 틴 30', 'SNS와 학습 콘텐츠를 자주 이용하는 청소년을 위한 요금제',
    30000, 'LTE_5G', 'TEEN', 14, 18, 'LIMITED', 30720, 1000,
    'UNLIMITED', NULL, 'UNLIMITED', NULL,
    '비타 틴 30은 만 14세부터 18세까지 가입할 수 있는 월 30,000원의 청소년용 LTE·5G 모바일 요금제입니다. 매월 데이터 30GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 1Mbps 속도로 계속 이용할 수 있습니다. SNS, 음악 감상과 온라인 학습 콘텐츠를 자주 이용하는 청소년에게 적합합니다.',
    'ACTIVE'
),
(
    'VITA-TEEN-70', '비타 틴 70', '영상과 게임 이용량이 많은 청소년을 위한 요금제',
    39000, 'LTE_5G', 'TEEN', 14, 18, 'LIMITED', 71680, 3000,
    'UNLIMITED', NULL, 'UNLIMITED', NULL,
    '비타 틴 70은 만 14세부터 18세까지 가입할 수 있는 월 39,000원의 청소년용 LTE·5G 모바일 요금제입니다. 매월 데이터 70GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 3Mbps 속도로 계속 이용할 수 있습니다. 동영상 스트리밍과 모바일 게임을 자주 이용하는 청소년에게 적합합니다.',
    'ACTIVE'
),
(
    'VITA-YOUTH-100', '비타 유스 100', '대용량 데이터가 필요한 청년층을 위한 요금제',
    50000, 'LTE_5G', 'YOUTH', 19, 34, 'LIMITED', 102400, 5000,
    'UNLIMITED', NULL, 'UNLIMITED', NULL,
    '비타 유스 100은 만 19세부터 34세까지 가입할 수 있는 월 50,000원의 청년용 LTE·5G 모바일 요금제입니다. 매월 데이터 100GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 5Mbps 속도로 계속 이용할 수 있습니다. 고화질 영상 스트리밍과 모바일 게임 등으로 데이터를 많이 사용하는 청년에게 적합합니다.',
    'ACTIVE'
),
(
    'VITA-SENIOR-50', '비타 시니어 50', '영상통화와 콘텐츠 이용량이 많은 시니어를 위한 요금제',
    41000, 'LTE_5G', 'SENIOR', 65, NULL, 'LIMITED', 51200, 3000,
    'UNLIMITED', NULL, 'UNLIMITED', NULL,
    '비타 시니어 50은 만 65세 이상 사용자를 위한 월 41,000원의 LTE·5G 모바일 요금제입니다. 매월 데이터 50GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 3Mbps 속도로 계속 이용할 수 있습니다. 영상통화, 동영상 시청과 다양한 모바일 콘텐츠를 자주 이용하는 시니어에게 적합합니다.',
    'ACTIVE'
);
