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

## EC2 dev/prod 배포 (로컬에서 실행 금지 — EC2 전용)

postgres는 컨테이너가 아니라 RDS(`vita-db`, DB는 `vita_dev`/`vita_prod`로 분리)를 쓴다. 최초 1회, EC2 안에서:

```bash
cp .env.dev.example .env.dev    # 또는 .env.prod.example → .env.prod
# DB_PASSWORD, JWT_SECRET을 CHANGE_ME에서 실제 값으로 채우기
docker compose -f docker-compose.dev.yml up -d --build    # dev는 8080, prod는 docker-compose.prod.yml로 8000
```

RDS 보안그룹(`vita-rds-sg`)이 EC2 보안그룹(`vita-ec2-sg`)의 5432 포트를 허용해야 연결된다. 스키마는
로컬과 마찬가지로 Flyway가 최초 기동 시 자동 적용한다(수동 SQL 불필요).

## CI/CD (GitHub Actions)

`develop` push → dev 자동 배포(`.github/workflows/deploy-dev.yml`), `main` push → prod 자동 배포
(`.github/workflows/deploy-prod.yml`). GitHub Actions가 SSH로 EC2에 접속해 `git pull` +
`docker compose up -d --build`를 그대로 실행하는 구조 — 별도 이미지 레지스트리 없음.

**최초 1회 설정 필요**
- GitHub 레포 Settings → Secrets and variables → Actions에 등록:
  - `EC2_HOST`: EC2 퍼블릭 DNS/IP
  - `EC2_SSH_KEY`: `vita-key.pem` 파일 내용 전체(그대로 복붙)
- EC2 보안그룹(`vita-ec2-sg`)의 SSH(22) 인바운드 소스를 **본인 IP → `0.0.0.0/0`으로 넓혀야 함** —
  GitHub Actions 러너는 고정 IP가 아니라 매번 다른 IP에서 접속하기 때문. 비밀번호 인증이 아니라
  키 기반 인증이라 무차별 대입 공격 위험은 낮지만, 주기적으로 EC2 로그인 시도 로그(`/var/log/secure`)
  정도는 확인 권장.
- EC2에 `~/Vita-BE`로 레포가 이미 clone되어 있어야 함(이번 세션에서 완료됨)

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
