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

**프론트/브라우저에서 실제로 호출할 땐 8080/8000이 아니라 아래 HTTPS 주소를 쓸 것** — "HTTPS
(Nginx + Let's Encrypt)" 절 참고.

## HTTPS (Nginx + Let's Encrypt)

프론트(Vercel, HTTPS)가 브라우저에서 백엔드를 직접 호출하려면 백엔드도 HTTPS여야 한다 — HTTPS
페이지에서 HTTP로 나가는 요청은 브라우저가 Mixed Content로 차단한다(CORS와는 별개 문제). EC2가
한 대뿐이라 로드밸런서(ALB) 대신, **EC2 안에 Nginx를 리버스 프록시로 세우고 Let's Encrypt 무료
인증서로 HTTPS를 처리**한다.

**접속 주소**

| 환경 | URL | 내부적으로 전달되는 곳 |
| --- | --- | --- |
| prod | `https://54-116-34-131.sslip.io` (443, 기본 포트라 생략 가능) | `127.0.0.1:8000` |
| dev | `https://54-116-34-131.sslip.io:8443` | `127.0.0.1:8080` |

**왜 `sslip.io`를 쓰는지**: EC2의 AWS 기본 도메인(`*.compute.amazonaws.com`)은 Let's Encrypt가
정책상 인증서 발급을 거부한다. `sslip.io`는 IP를 도메인처럼 쓰게 해주는 무료 서비스라(`54-116-34-131.sslip.io` →
자동으로 `54.116.34.131`) 별도 도메인 구매 없이 인증서를 받을 수 있다.

⚠️ **반드시 하이픈(`-`) 버전으로만 접속할 것.** 점(`.`) 버전(`54.116.34.131.sslip.io`)도 DNS는
같은 IP로 풀리지만, 인증서는 하이픈 버전 이름으로만 발급받았기 때문에 점 버전으로 접속하면
`SEC_E_WRONG_PRINCIPAL`(인증서 이름 불일치)로 접속이 거부된다 — 실제 테스트로 확인됨.

**설정 요약** (EC2에서 1회 진행, 이미 완료됨)
- `vita-ec2-sg`에 `80`(인증서 발급용), `443`(prod), `8443`(dev) 인바운드 추가, 전부 `0.0.0.0/0`
- Nginx 설치, Certbot은 Amazon Linux 2023에 기본 패키지가 없어 Python venv(`/opt/certbot`)로 설치
- `certbot certonly --nginx -d 54-116-34-131.sslip.io`로 인증서 발급
- `/etc/nginx/conf.d/vita.conf`에 443→8000, 8443→8080 리버스 프록시 설정. `location /`에
  `X-Forwarded-Proto`/`X-Forwarded-Host`/`X-Forwarded-Port`를 모두 넘겨야 한다(아래 참고).
- systemd 타이머(`certbot-renew.timer`, 매일 03/15시 체크)로 자동 갱신 — 인증서는 90일마다 만료.
  `sudo /opt/certbot/bin/certbot renew --dry-run`으로 정상 동작 확인됨

**TODO**: `vita-ec2-sg`에서 `8080`, `8000` 인바운드 규칙 삭제 필요 — 지금은 예전 HTTP 직접 접근
(`http://ec2-...:8080` 등)도 여전히 열려 있는 상태. Nginx는 로컬(127.0.0.1)로 컨테이너에 붙는
구조라 이 두 포트를 막아도 서비스엔 영향 없음 — HTTPS 경로만 쓰도록 강제하려면 닫아야 한다.

**Swagger "Try it out"이 Failed to fetch로 실패하는 문제 (2026-09-16 발견/수정)**: springdoc이
OpenAPI 문서의 서버 주소를 자동 추론하는데, Nginx가 원 요청의 프로토콜/호스트/포트 정보를
백엔드에 안 넘겨주면 Spring이 이를 잘못 판단해서(예: dev `:8443`으로 접속했는데 서버 주소를
포트 없는 443짜리로 잘못 생성) Swagger의 요청이 엉뚱한 곳(주로 prod 443)으로 나가버린다.
해결을 위해 두 가지가 다 필요하다:
1. `application.yml`에 `server.forward-headers-strategy: framework` 추가 (Spring이 forwarded
   헤더를 신뢰하게 함) — 완료됨.
2. `/etc/nginx/conf.d/vita.conf`의 두 `server` 블록 `location /`에 아래 두 줄 추가 — 완료됨:
   ```nginx
   proxy_set_header X-Forwarded-Host $host;
   proxy_set_header X-Forwarded-Port $server_port;
   ```
   (`$server_port`는 nginx가 그 블록에서 실제 `listen`한 포트를 자동으로 넣어주므로 443/8443
   블록에 그대로 써도 됨)

수정 후 컨테이너 재시작은 필요 없고 `sudo nginx -t && sudo systemctl reload nginx`만 하면 된다.

## RDS 직접 접근이 필요할 때 (SSH 터널링)

RDS는 퍼블릭 액세스가 꺼져 있고 EC2에서만 접근 가능하다(보안그룹). 본인 노트북에서 직접
`psql -h vita-db...`를 치면 연결 자체가 안 된다 — DB 계정/비밀번호를 알아도 네트워크 단에서
막혀있기 때문. FAQ 시드 스크립트처럼 로컬에서 AWS(dev) RDS에 직접 데이터를 넣어야 하는
경우(BE2 등)엔 EC2를 경유하는 SSH 터널을 열어야 한다.

**1. 터널 열기** (별도 터미널 창에 하나 띄워두고, 작업 끝날 때까지 그 창은 그대로 둔다 — `Ctrl+C`로 종료)
```bash
ssh -i "vita-key.pem" -L 5433:vita-db.cbouowac2f97.ap-northeast-2.rds.amazonaws.com:5432 ec2-user@ec2-54-116-34-131.ap-northeast-2.compute.amazonaws.com -N
```
로컬 `5433` 포트로 들어오는 트래픽을 EC2를 거쳐 RDS의 `5432`로 그대로 전달해준다 (로컬
5432는 이미 `docker-compose.local.yml`의 로컬 postgres가 쓰고 있어서 5433으로 뺐다).

**2. 다른 터미널에서 localhost:5433으로 접속** (실제로는 RDS에 붙는 것과 동일)
```bash
psql -h localhost -p 5433 -U vita -d vita_dev
```
스크립트에서 접속하는 경우엔 `DB_URL=jdbc:postgresql://localhost:5433/vita_dev`처럼 호스트만
`localhost:5433`으로 바꿔서 쓰면 된다. 계정/비밀번호는 `.env.dev`와 동일(`vita` / 실제 RDS 비밀번호).

**주의**: `vita-key.pem`은 EC2 SSH 접속 키라 아무한테나 공유하면 안 된다 — 필요한 사람에게 직접
전달하거나, 별도 팀원용 키를 EC2 `~/.ssh/authorized_keys`에 추가해서 개인별로 발급하는 걸 권장.

## 로밍 FAQ 정책 JSONL 적재

정책 원본은 `src/main/resources/data/faq/policy_cleaned.jsonl`, 생성 초안은
`data/faq/raw/faq_roaming_test_raw.jsonl`, 최종 적재본은
`data/faq/cleaned/faq_roaming_test.jsonl`에서 관리한다. 현재 최종 적재본에는 로밍 6개
subcategory의 검수된 FAQ 23건이 들어 있다. 애플리케이션은 기본적으로 적재기를 실행하지 않으며,
아래처럼 명시적으로 켠 경우에만 시작 시 최종 JSONL을 검증하고 `faq` 테이블에 적재한다.

로컬 PostgreSQL:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:DB_URL = "jdbc:postgresql://localhost:5432/vita_local"
$env:DB_USERNAME = "vita"
$env:DB_PASSWORD = "local1234"
$env:FAQ_IMPORT_ENABLED = "true"
.\gradlew.bat bootRun
```

AWS dev는 먼저 위의 SSH 터널을 연 뒤 별도 터미널에서 같은 코드를 `aws-dev` profile로 실행한다.
비밀번호는 실제 RDS 암호를 환경변수로만 전달한다.

```powershell
$env:SPRING_PROFILES_ACTIVE = "aws-dev"
$env:DB_USERNAME = "vita"
$env:DB_PASSWORD = "<RDS 비밀번호>"
$env:FAQ_IMPORT_ENABLED = "true"
.\gradlew.bat bootRun
```

적재기는 category/subcategory/question으로 안정적인 내부 키를 만들어 재실행해도 같은 FAQ를
중복 추가하지 않는다. 같은 질문의 답변이 바뀌면 기존 임베딩을 비워 재생성 대상으로 만들며,
내용이 같으면 임베딩을 보존한다. 질문 문구 변경까지 같은 행으로 관리하려면 JSONL에 선택 필드인
`faq_id`를 지정한다. 다른 파일을 사용할 때는
`FAQ_IMPORT_RESOURCE=file:C:/path/to/faq_cleaned.jsonl`처럼 지정할 수 있다.

BE3 검색 코드는 `com.vita.embedding.EmbeddingProvider`를 주입받아 `embedQuery()`를 호출하면 된다.
FAQ 임베딩 적재 구현은 같은 인터페이스의 `embedDocument()`를 사용한다. 실제 E5/Bedrock 구현체는
모델과 호출 방식이 확정된 뒤 별도 `@Component`로 추가한다.

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
