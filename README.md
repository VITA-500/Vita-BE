# Vita-BE

VITA 백엔드. 패키지 구조/코딩 컨벤션은 [../docs/08_개발표준.md] 기준.

`src/main/resources/db/migration/V1__init_extension_and_core_tables.sql`에 실제 스키마(users,
user_oauth, faq, store, chat_session, chat_message, chat_message_faq_ref, store_reservation)가
확정되어 있다. 도메인 코드(auth/faq/search/chat/store)는 아직 없음 — 각자 담당 도메인 폴더를
`common/`의 패턴(entity extends BaseTimeEntity, repository/service/controller/dto 계층, 도메인
예외는 폴더 루트에 위치)대로 만들어 채워나가면 된다.

## 로컬 DB 환경 구축 (스키마 변경 후 최초 1회 또는 pull마다)

```bash
git pull origin main
cp .env.local.example .env.local   # 이미 있으면 생략
docker compose -f docker-compose.local.yml down -v   # 기존 로컬 볼륨 완전 초기화 (스키마 바뀌었으므로 필수)
docker compose -f docker-compose.local.yml up -d --build
```

`down -v`로 볼륨(`vita-be_postgres-data`)까지 지워야 Flyway가 새 `V1`을 처음부터 다시 적용한다 —
기존 볼륨이 남아있으면 이미 적용된 걸로 기록된 과거 스키마 위에서 엇갈릴 수 있다. 기동 후 확인:

```bash
docker exec vita-postgres-local psql -U vita -d vita_local -c "\dt"
```

`users`, `user_oauth`, `faq`, `store`, `chat_session`, `chat_message`, `chat_message_faq_ref`,
`store_reservation`, `flyway_schema_history`가 보이면 정상이다.

## 시작하기 (일반)

```bash
./gradlew compileJava   # 컴파일 확인
./gradlew test          # 테스트
./gradlew bootRun        # 로컬 Postgres가 떠 있어야 함
```

## 패키지 구조

```
com.vita/
├── VitaApplication.java
└── common/            공통 응답 포맷(ErrorResponse — 성공 응답은 wrapper 없이 DTO 그대로 반환),
                        전역 예외 처리(BusinessException + GlobalExceptionHandler), 공통 엔티티
                        (BaseTimeEntity), 공통 페이징(PageRequest/PageResponse, sortBy 화이트리스트)
```

각 담당자는 `auth`, `faq`, `search`, `chat`, `store` 패키지를 아래 형태로 만들면 된다
(08_개발표준.md 1절 기준):

```
{도메인}/
├── {Domain}NotFoundException.java   도메인 예외는 서브패키지 없이 도메인 루트에 위치
├── entity/{Domain}.java             BaseTimeEntity 상속
├── repository/{Domain}Repository.java
├── service/{Domain}Service.java
├── controller/{Domain}Controller.java
└── dto/{Domain}Response.java, {Domain}CreateRequest.java 등
```
