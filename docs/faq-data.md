# 합성 FAQ 데이터 공유 및 Java 적재

팀 공용 FAQ 1,000건과 이 문서를 Java 백엔드 코드와 함께 Git에서 관리한다. 노션에는 개요와 이 문서의 저장소 링크를 연결한다. Python 설치나 별도 Python 적재 스크립트는 필요하지 않다.

## 공유 파일과 실행 구조

| 파일 | 역할 |
|---|---|
| `src/main/resources/data/faq/cleaned/faq_all_cleaned.jsonl` | 정제된 합성 FAQ 1,000건 |
| `src/main/resources/data/faq/category/faq_generation_categories.json` | 10개 메인카테고리·43개 하위 분류 조합과 제외 기준 |
| `src/main/java/com/vita/faq/batch/FaqJsonlReader.java` | JSONL 읽기 및 기본 필드·ID·출처 검증 |
| `src/main/java/com/vita/faq/batch/FaqDataImporter.java` | Spring JDBC로 PostgreSQL 추가·갱신 |
| `src/main/java/com/vita/faq/batch/FaqDataImportRunner.java` | 적재 설정이 켜졌을 때 읽기·저장 순서 실행 |
| `docs/faq-data.md` | 실행 방법과 전체 분류표 |

실행 흐름: JSONL → FaqJsonlReader → FaqDataImporter → PostgreSQL의 faqs 테이블.

팀원이 같은 Vita-BE 코드를 받았다면 JSONL 파일 하나를 정해진 위치에 놓고 실행하면 된다. JSONL만으로 DB에 자동 저장되지는 않으며, 백엔드 코드와 DB 연결 설정이 필요하다. 검증 보고서는 정제 이력용으로만 보관하며 적재에 필요하지 않다.

## Java/JDBC를 유지하는 이유

팀에서 사용하는 Java/Spring 환경에 적재 기능이 이미 있다. 별도 언어와 DB 드라이버를 관리하지 않고 기존 코드를 재사용한다. JPA로 구현할 수도 있지만, 현재 JDBC 코드가 source_faq_id 기준의 PostgreSQL ON CONFLICT와 임베딩 무효화를 처리하므로 JPA로 다시 작성하지 않는다.

- JSONL의 faq_id는 DB의 source_faq_id에 저장한다. 숫자 기본키 id와는 다르다.
- 같은 source_faq_id가 있으면 해당 행을 갱신한다. 재실행해도 같은 ID의 행이 추가되지 않는다.
- 기존 코드에서는 내용이 같아도 updated_at은 갱신된다. 완료 로그의 count는 신규 행 수가 아니라 처리한 행 수다.
- 질문·답변이 바뀌면 기존 임베딩과 생성 메타데이터를 비워 재생성 대상으로 만든다. 본문이 같으면 임베딩을 유지한다.
- 입력 ID에 해당하는 행은 ACTIVE로 저장한다. 같은 ID의 INACTIVE 행도 활성화한다.
- 파일에 없는 행은 삭제하거나 비활성화하지 않는다. 과거 데이터가 있으면 DB 전체 건수는 1,000건을 넘을 수 있다.
- 파일을 먼저 읽고 검증한 뒤 트랜잭션 안에서 저장하며 저장 도중 오류가 발생하면 이번 적재를 롤백한다.
- 이 데이터는 검색 테스트용 합성 FAQ다. source_policy_ids는 SYNTHETIC-GENERATED이며 공식 정책으로 사용하지 않는다.

## 1. DB 없이 데이터 검증

Java 21을 준비하고 Vita-BE 루트에서 실행한다.

Windows PowerShell:

```powershell
.\gradlew.bat test --tests "com.vita.faq.batch.*"
```

macOS/Linux:

```sh
./gradlew test --tests 'com.vita.faq.batch.*'
```

공용 데이터 1,000건, ID·질문 중복, 합성 출처, 허용 분류 43개 조합과 기본 적재 설정을 검사한다. 의미 중복 및 정책 진위는 자동 테스트만으로 판정하지 않는다. Java reader는 합성·수집 데이터 모두 공통 FaqTaxonomy로 메인·서브카테고리 조합을 검증한다. 분류의 단일 기준은 faq_generation_categories.json이며, 출처에 따른 검증 예외는 없다. 공용 파일 대상 테스트에서는 합성 출처와 43개 조합의 전체 포함 여부도 확인한다.

## 2. 로컬 DB 준비

```text
docker compose -f docker-compose.local.yml up -d postgres redis
```

아래 적재 명령으로 백엔드를 실행하면 Flyway 마이그레이션 후 적재가 실행된다. 별도의 테이블 생성 스크립트나 Python 드라이버 설치는 필요하지 않다.

## 3. 로컬 적재 실행

Windows PowerShell:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/vita_local"
$env:DB_USERNAME = "vita"
$env:DB_PASSWORD = "local1234"
.\gradlew.bat bootRun --args="--faq.import.enabled=true --faq.import.resource=classpath:data/faq/cleaned/faq_all_cleaned.jsonl --faq.embedding.enabled=false --faq.collection.enabled=false"
```

위 비밀번호는 저장소의 로컬 Compose 개발 환경 예제 값이다. 개인 환경에서 변경했다면 그 값으로 설정한다. RDS 비밀번호를 문서나 Git에 기록하지 않는다.

macOS/Linux:

```sh
DB_URL=jdbc:postgresql://localhost:5432/vita_local DB_USERNAME=vita DB_PASSWORD=local1234 ./gradlew bootRun --args='--faq.import.enabled=true --faq.import.resource=classpath:data/faq/cleaned/faq_all_cleaned.jsonl --faq.embedding.enabled=false --faq.collection.enabled=false'
```

로그의 `FAQ JSONL 적재 완료: ... count=1000`을 확인한다. 명령은 적재 후에도 백엔드를 계속 실행하므로 필요한 경우 Ctrl+C로 종료한다. 다음 일반 실행에서는 FAQ_IMPORT_ENABLED를 false로 지정하거나 적재 실행 인자를 제거한다. 환경변수에 true가 남아 있으면 재실행 시에도 적재한다.

application.yml의 기본값은 FAQ_IMPORT_ENABLED=false다. 그러나 개인 .env.local이나 실행 환경이 이를 덮어쓸 수 있다. 확인 당시 개인 .env.local에는 true가 설정돼 있었다. bootRun은 .env.local을 자동으로 읽지 않으며 docker-compose.local.yml의 backend 서비스는 해당 파일을 env_file로 읽는다.

저장소 밖의 JSONL을 적재하려면 faq.import.resource에 `file:C:/data/faq_all_cleaned.jsonl` 또는 `file:/data/faq_all_cleaned.jsonl`처럼 파일 위치를 지정한다.

## 4. RDS 적재

같은 Java 코드를 사용하고 DB_URL, DB_USERNAME, DB_PASSWORD를 RDS 연결 정보로 바꾼다. DB_URL은 `jdbc:postgresql://<호스트>:5432/<DB명>` 형식이며 팀의 TLS 설정과 인증서를 적용한다. 접근 가능한 개발 환경에서 위와 같은 bootRun 명령을 실행한다. RDS 네트워크 접근과 Flyway 마이그레이션 권한이 필요하다.

로컬 명령의 DB_URL을 그대로 사용하면 로컬에 적재된다. 임베딩을 생성할 때도 적재한 DB와 같은 연결 설정을 사용해야 한다.

## 5. 적재 확인 및 임베딩

```sql
SELECT count(*) AS synthetic_faqs,
       count(*) FILTER (WHERE status = 'ACTIVE') AS active_faqs,
       count(*) FILTER (WHERE embedding IS NOT NULL) AS embedded_faqs
FROM faqs
WHERE source_policy_ids = ARRAY['SYNTHETIC-GENERATED']::text[];
```

이 SQL은 이전 합성 데이터가 남아 있다면 그것도 포함한다. 처리 로그 count=1000 역시 기존 데이터의 삭제나 전체 DB가 정확히 1,000건이라는 뜻은 아니다.

새 FAQ는 임베딩이 비어 있어 벡터 검색에서 제외된다. 적재 확인 후 검색 테스트가 필요할 때 별도로 실행한다.

```text
docker compose -f docker-compose.embedding.yml up -d
```

모델 서버 준비 후 Windows에서:

```powershell
.\gradlew.bat bootRun --args="--faq.import.enabled=false --faq.collection.enabled=false --faq.embedding.enabled=true"
```

macOS/Linux는 ./gradlew를 사용한다. 현재 임베딩은 intfloat/multilingual-e5-base, 768차원이다. 기존 작업은 DB의 모든 ACTIVE이면서 embedding이 비어 있는 FAQ를 처리한다. 검색 임계값 0.83은 과거 소량 데이터 기준의 잠정값으로, 새 1,000건으로 검색 평가 후 조정한다.

## 검증 상태

기존 소량 데이터 제거 후 관련 Java 테스트 17개가 통과했다. 이후 사용하지 않는 정책 파서와 테스트 7개를 제거하여 현재 FAQ 배치 테스트는 10개다. Java 적재 코드는 변경하지 않았다. 실제 로컬/RDS 적재와 임베딩 생성의 완료 여부는 별도 조회로 확인해야 한다.

## 분류표
아래 표는 JSONL과 분류 파일을 기준으로 작성했다. 동일한 이름의 하위 분류도 메인카테고리와의 조합으로 구분한다.

| 메인카테고리 | 서브카테고리 | 하위 분류 수 | FAQ 수 |
|---|---|---:|---:|
| 유심(USIM) 업데이트 · 교체 | 시행 배경, 업데이트와 교체 대상 · 일정, 온라인 간편 업데이트, 매장방문, 그 외 안내사항, 유심 무료 교체 쿠폰 | 6 | 142 |
| 모바일 | 요금제, 단말기(휴대폰), 모바일서비스 | 3 | 81 |
| 인터넷/IPTV | 인터넷 상품안내, 인터넷 요금안내, 인터넷 장비안내, 인터넷 부가서비스, 인터넷 속도/품질, 인터넷 장애/고장, IPTV 상품안내, IPTV 요금안내, IPTV 장비안내, IPTV 부가서비스, IPTV 주요서비스, IPTV 장애/고장 | 12 | 268 |
| 전화 | 인터넷전화, 수신자 부담전화 | 2 | 52 |
| 결합 할인 | 유무선 결합, 유선 결합, 무선 결합, 신규 가입 불가 | 4 | 101 |
| 해외로밍 | 서비스안내, WCDMA/GSM | 2 | 34 |
| 소상공인 | 인터넷, 전화, CCTV, IPTV | 4 | 108 |
| 가입 및 변경 | 모바일, 인터넷, TV, 인터넷전화, 군입대 일시정지, 설치장소 변경 | 6 | 121 |
| 요금 및 납부 | 요금납부, 요금조회 | 2 | 39 |
| 서비스안내 | 통화품질, 정보변경 | 2 | 54 |
| 합계 | 10개 메인카테고리 | 43 | 1,000 |
