# 요금제 시드 임베딩

V8이 plans 테이블을 만들고 V9가 가상 요금제 15건을 적재한다. 추가 마이그레이션은 필요 없다.
Flyway 적용 후 PlanEmbeddingRunner가 활성화된 경우에만 실행된다. 기본값은 비활성이다.

## 처리 정책

- ACTIVE이며 embedding이 NULL인 요금제의 description 전체를 사용한다.
- 공통 E5EmbeddingProvider가 passage 접두사를 붙인다. 모델은 intfloat/multilingual-e5-base, 차원은 768이다.
- 요금제 정책 버전은 v1(description 전체)이다. FAQ의 v2와 독립적으로 관리한다.
- embedding, embedding_model, embedding_version, embedded_at, updated_at을 한 UPDATE로 저장한다.
- 이미 임베딩된 행은 재실행해도 건너뛴다. 중간 실패 시 완료된 행은 보존되고 다음 실행은 미완료 행을 처리한다.
- 생성 중 설명이나 상태가 변경되거나 다른 실행에서 저장하면 해당 저장을 건너뛴다. 경고 로그를 확인하고 필요시 다시 실행한다.
- batch-size는 조회 단위다. 모델은 요금제별로 호출하며 한 번 실행하면 모든 미완료 행을 순회한다.
- 설명을 나중에 수정하는 기능에서는 같은 트랜잭션에서 embedding 및 임베딩 메타데이터를 NULL로 초기화해야 한다. 기존 벡터의 자동 변경 감지는 제공하지 않는다.

## 로컬 실행 (PowerShell, 저장소 루트)

PostgreSQL과 임베딩 서버를 먼저 실행한다.

```powershell
docker compose -f docker-compose.local.yml up -d postgres
docker compose -f docker-compose.embedding.yml up -d
$env:DB_URL = 'jdbc:postgresql://localhost:5432/vita_local'
$env:DB_USERNAME = 'vita'
$env:DB_PASSWORD = 'local1234'
$env:EMBEDDING_BASE_URL = 'http://localhost:8081'
$env:FAQ_IMPORT_ENABLED = 'false'
$env:FAQ_EMBEDDING_ENABLED = 'false'
$env:PLAN_EMBEDDING_ENABLED = 'true'
$env:PLAN_EMBEDDING_BATCH_SIZE = '100'
.\gradlew.bat bootRun
```

기존 애플리케이션에서 사용하는 AWS 등 나머지 환경 설정도 필요하다. 기존 서버와 포트가 겹치면 SERVER_PORT를 변경한다.
완료 로그 `요금제 임베딩 저장 완료: count=15`를 확인한다(기존 임베딩 수에 따라 달라짐). 같은 상태에서 재실행하면 count=0이다.
작업 후 PLAN_EMBEDDING_ENABLED를 false로 되돌린다. Docker backend에서는 EMBEDDING_BASE_URL을 http://host.docker.internal:8081로 설정한다.

## 확인

```sql
SELECT plan_code, status, vector_dims(embedding) AS dimensions,
       embedding_model, embedding_version, embedded_at
FROM plans ORDER BY id;
```

## 테스트

```powershell
.\gradlew.bat test --tests 'com.vita.plan.embedding.*' --tests 'com.vita.embedding.*'
```

실제 pgvector 저장 테스트는 PLAN_TEST_DB_URL, PLAN_TEST_DB_USERNAME, PLAN_TEST_DB_PASSWORD를 지정할 때 실행한다.
기존 plans 대신 연결 전용 임시 테이블을 사용하고 종료 시 롤백한다. 설정이 없으면 해당 통합 테스트는 건너뛴다.
