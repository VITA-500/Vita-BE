-- plans 테이블의 구조화된 상품 정보를 검색 및 답변용 description에 빠짐없이 반영한다.
-- 기존 시드에 정의된 이용 특성은 유지하고, 컬럼으로 확인할 수 없는 혜택이나 조건은 추가하지 않는다.

UPDATE plans
SET description = '비타 라이트 5는 월 25,000원의 일반 사용자용 LTE·5G 모바일 요금제입니다. 매월 데이터 5GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 400Kbps 속도로 계속 이용할 수 있습니다. 와이파이를 주로 사용하고 외부에서는 메신저나 웹 검색 위주로 이용하는 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-LITE-5';

UPDATE plans
SET description = '비타 라이트 10은 월 31,000원의 일반 사용자용 LTE·5G 모바일 요금제입니다. 매월 데이터 10GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 1Mbps 속도로 계속 이용할 수 있습니다. 메신저와 SNS를 자주 사용하고 가끔 동영상을 시청하는 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-LITE-10';

UPDATE plans
SET description = '비타 밸런스 20은 월 37,000원의 일반 사용자용 LTE·5G 모바일 요금제입니다. 매월 데이터 20GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 1Mbps 속도로 계속 이용할 수 있습니다. 웹 검색, SNS, 음악 감상과 짧은 영상 시청을 고르게 이용하는 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-BALANCE-20';

UPDATE plans
SET description = '비타 밸런스 40은 월 43,000원의 일반 사용자용 LTE·5G 모바일 요금제입니다. 매월 데이터 40GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 1Mbps 속도로 계속 이용할 수 있습니다. 영상 시청과 SNS를 자주 이용하지만 완전 무제한 데이터까지는 필요하지 않은 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-BALANCE-40';

UPDATE plans
SET description = '비타 플러스 80은 월 51,000원의 일반 사용자용 LTE·5G 모바일 요금제입니다. 매월 데이터 80GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 3Mbps 속도로 계속 이용할 수 있습니다. 출퇴근 중 동영상이나 음악 스트리밍을 자주 이용하는 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-PLUS-80';

UPDATE plans
SET description = '비타 플러스 120은 월 59,000원의 일반 사용자용 LTE·5G 모바일 요금제입니다. 매월 데이터 120GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 5Mbps 속도로 계속 이용할 수 있습니다. 고화질 영상과 모바일 게임 등으로 데이터를 많이 사용하는 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-PLUS-120';

UPDATE plans
SET summary = '여러 기기에서 데이터를 공유하고 나눠 쓰는 사용자를 위한 무제한 요금제',
    description = '비타 맥스 쉐어는 월 75,000원의 LTE·5G 데이터 무제한 모바일 요금제입니다. 음성통화와 문자를 무제한으로 제공하며, 스마트폰과 태블릿, 워치 등 여러 기기에서 데이터를 공유하거나 나눠 쓰는 데이터 쉐어링이 필요하고 데이터 사용량이 많은 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-MAX-SHARE';

UPDATE plans
SET description = '비타 유스 30은 만 19세부터 34세까지 가입할 수 있는 월 35,000원의 청년용 LTE·5G 모바일 요금제입니다. 매월 데이터 30GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 1Mbps 속도로 계속 이용할 수 있습니다. SNS, 음악 감상과 일상적인 영상 시청을 즐기는 청년 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-YOUTH-30';

UPDATE plans
SET description = '비타 유스 70은 만 19세부터 34세까지 가입할 수 있는 월 45,000원의 청년용 LTE·5G 모바일 요금제입니다. 매월 데이터 70GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 3Mbps 속도로 계속 이용할 수 있습니다. 동영상 스트리밍과 모바일 게임을 자주 이용하는 청년 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-YOUTH-70';

UPDATE plans
SET description = '비타 시니어 8은 만 65세 이상 사용자를 위한 월 23,000원의 LTE·5G 모바일 요금제입니다. 매월 데이터 8GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 400Kbps 속도로 계속 이용할 수 있습니다. 통화, 메신저와 간단한 정보 검색을 주로 이용하는 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-SENIOR-8';

UPDATE plans
SET description = '비타 시니어 20은 만 65세 이상 사용자를 위한 월 33,000원의 LTE·5G 모바일 요금제입니다. 매월 데이터 20GB와 음성통화·문자를 무제한으로 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 1Mbps 속도로 계속 이용할 수 있습니다. 영상통화와 동영상 시청을 비교적 자주 이용하는 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-SENIOR-20';

UPDATE plans
SET description = '비타 키즈 5는 만 18세 이하 사용자를 위한 월 19,000원의 어린이용 LTE·5G 모바일 요금제입니다. 매월 데이터 5GB와 음성통화 100분, 문자 100건을 제공하며, 기본 데이터를 모두 사용한 뒤에는 최대 400Kbps 속도로 계속 이용할 수 있습니다. 보호자와의 연락과 학습 콘텐츠 이용이 필요한 어린이에게 적합합니다.'
WHERE plan_code = 'VITA-KIDS-5';

UPDATE plans
SET description = '비타 워치 1은 월 11,000원의 스마트워치 전용 LTE 요금제입니다. 매월 데이터 1GB를 제공하고 음성통화와 문자는 제공하지 않으며, 기본 데이터를 모두 사용하면 데이터 이용이 차단됩니다. 스마트워치에서 알림 확인과 간단한 데이터 통신을 주로 사용하는 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-WATCH-1';

UPDATE plans
SET description = '비타 태블릿 20은 월 22,000원의 태블릿 전용 LTE·5G 데이터 요금제입니다. 매월 데이터 20GB를 제공하고 음성통화와 문자는 제공하지 않으며, 기본 데이터를 모두 사용하면 데이터 이용이 차단됩니다. 태블릿으로 온라인 학습, 웹 검색과 동영상 시청을 이용하는 사용자에게 적합합니다.'
WHERE plan_code = 'VITA-TABLET-20';

-- 설명이 변경된 요금제만 PlanEmbeddingRunner가 다시 처리하도록 기존 벡터를 무효화한다.
UPDATE plans
SET embedding = NULL,
    embedding_model = NULL,
    embedding_version = NULL,
    embedded_at = NULL,
    updated_at = now()
WHERE plan_code IN (
    'VITA-LITE-5',
    'VITA-LITE-10',
    'VITA-BALANCE-20',
    'VITA-BALANCE-40',
    'VITA-PLUS-80',
    'VITA-PLUS-120',
    'VITA-MAX-SHARE',
    'VITA-YOUTH-30',
    'VITA-YOUTH-70',
    'VITA-SENIOR-8',
    'VITA-SENIOR-20',
    'VITA-KIDS-5',
    'VITA-WATCH-1',
    'VITA-TABLET-20'
);
