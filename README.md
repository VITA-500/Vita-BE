# 🤖 VITA
> 가상 통신사 FAQ 기반 AI 상담 + 위치 기반 매장 안내 서비스

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)](https://www.postgresql.org/)
[![pgvector](https://img.shields.io/badge/pgvector-vector_search-4169E1)](https://github.com/pgvector/pgvector)
[![Redis](https://img.shields.io/badge/Redis-7.x-red)](https://redis.io/)
[![AWS Bedrock](https://img.shields.io/badge/LLM-AWS_Bedrock-orange)](https://aws.amazon.com/bedrock/)
[![Docker](https://img.shields.io/badge/Docker-latest-blue)](https://www.docker.com/)

---

## 목차

1. 프로젝트 소개
2. 팀 구성 및 일정
3. 기술 스택
4. 시스템 아키텍처 (설계)
5. ERD (설계)
6. 기능 및 API 설계
7. 핵심 설계 포인트
8. 현재 구현 상태
9. 남은 로드맵

---

## 1. 프로젝트 소개

**"AI 상담 서비스 구현 프로젝트"** 과제를 바탕으로, 가상 통신 서비스의 FAQ 데이터와 매장 정보를 이용해 사용자 질문에 RAG(검색 증강 생성)로 자연어 답변을 하고, 위치 기반으로 가까운 매장을 안내하는 AI 상담·지도 서비스입니다.

**핵심 경험 포인트**: Vector DB 기반 검색, LLM 응답 생성, 지도 API 활용, 위치 기반 서비스 설계, FE/BE 협업 API 설계

**전제 조건** (과제 원문 기준)
- 통신 서비스 FAQ 1,000개 이상을 생성형 AI로 생성
- 생성한 FAQ는 Vector DB에 저장해 검색 가능하도록 구성
- 지도 API로 위치 기반 화면 구현
- LLM은 팀 협의로 AWS Bedrock 채택 (2026-09-16 확정)

---

## 2. 팀 구성 및 일정

**팀명**: 1조 : 500 (백엔드 6 · 프론트 2, 총 8인)

| 역할 | 담당 | 핵심 업무 |
|---|---|---|
| BE1 | 김재우 | 아키텍처/공통/인증 (회원가입, 로그인, JWT, 마이페이지) |
| BE2 | 김현정 | FAQ 데이터 생성/CRUD, 임베딩 변환 및 pgvector 저장 |
| BE3 | 이진희 | RAG 검색 (pgvector 유사도 검색, threshold, Context 구성) |
| BE4 | 정민주 | LLM/Chat API (Bedrock 연동, Prompt, 응답 상태관리) |
| BE5 | 안제홍 | 매장/위치 서비스 (매장 CRUD, 거리 계산, 지도 데이터) |
| BE6 (조장) | 김어진 | 통합테스트, DevOps |
| FE1 | 박해준 | 인증/AI Chat (로그인, 채팅, Streaming, 대화 기록) |
| FE2 | 정승민 | 지도/관리자/랜딩 UI |

**일정** (2026-09-09 ~ 2026-10-28, 약 7주)

| 기간 | 내용 |
|---|---|
| 9/9 ~ 9/15 | 주제 선정, 기획안 작성, 역할 분담, 공통 작업(패키지 구조, API 규격, DB 스키마 설계) |
| **9/16 ~ 9/29** | **Phase 1 (MVP)** — 인증, FAQ 생성/적재, RAG 검색, Chat API, 매장 CRUD, Docker 환경 구성 |
| 9/30 ~ 10/13 | Phase 2 (Core) — 통합 테스트, 임베딩 동기화, 관리자 API, CI/CD 파이프라인 구성 |
| 10/14 ~ 10/24 | Phase 3 (Hardening) — 예외 처리, 성능 점검, 배포 안정화 |
| 10/25 ~ 10/28 | 발표 준비 및 최종 점검 |

> 현재 시점은 Phase 1 초반이며, 이번 멘토링은 설계·기획 내용 위주로 진행합니다.

---

## 3. 기술 스택 (계획)

### Backend

| 분류 | 기술 |
|---|---|
| 언어 / 프레임워크 | ![Java](https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white) |
| 인증 | ![Spring Security](https://img.shields.io/badge/Spring%20Security-6DB33F?style=for-the-badge&logo=springsecurity&logoColor=white) ![JWT](https://img.shields.io/badge/JWT-000000?style=for-the-badge&logo=jsonwebtokens&logoColor=white) |
| LLM | ![AWS Bedrock](https://img.shields.io/badge/AWS%20Bedrock-FF9900?style=for-the-badge&logo=amazonaws&logoColor=white) |
| API 문서 | ![Swagger](https://img.shields.io/badge/Swagger-85EA2D?style=for-the-badge&logo=swagger&logoColor=black) |
| 테스트 | ![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=for-the-badge&logo=junit5&logoColor=white) |

### Database

| 분류 | 기술 |
|---|---|
| RDBMS + Vector DB | ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white) ![pgvector](https://img.shields.io/badge/pgvector-4169E1?style=for-the-badge&logoColor=white) |
| 캐시 | ![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white) |

### Infra

| 분류 | 기술 |
|---|---|
| 컨테이너 | ![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white) |
| 배포 | ![AWS EC2](https://img.shields.io/badge/AWS%20EC2-FF9900?style=for-the-badge&logo=amazonec2&logoColor=white) ![AWS RDS](https://img.shields.io/badge/AWS%20RDS-527FFF?style=for-the-badge&logo=amazonrds&logoColor=white) |
| HTTPS | ![Nginx](https://img.shields.io/badge/Nginx-009639?style=for-the-badge&logo=nginx&logoColor=white) ![Let's Encrypt](https://img.shields.io/badge/Let's%20Encrypt-003A70?style=for-the-badge&logo=letsencrypt&logoColor=white) |
| CI/CD | ![GitHub Actions](https://img.shields.io/badge/GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white) |
| 모니터링(선택) | ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white) ![Grafana](https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=grafana&logoColor=white) |

### Frontend

| 분류 | 기술 |
|---|---|
| 프레임워크 | ![Next.js](https://img.shields.io/badge/Next.js-16-000000?style=for-the-badge&logo=next.js&logoColor=white) ![React](https://img.shields.io/badge/React-19-61DAFB?style=for-the-badge&logo=react&logoColor=black) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white) |
| 스타일 | ![Tailwind CSS](https://img.shields.io/badge/Tailwind%20CSS-06B6D4?style=for-the-badge&logo=tailwindcss&logoColor=white) |
| 상태관리 / 데이터 페칭 | ![Zustand](https://img.shields.io/badge/Zustand-433E38?style=for-the-badge) ![TanStack Query](https://img.shields.io/badge/TanStack%20Query-FF4154?style=for-the-badge&logo=reactquery&logoColor=white) |
| 폼 / 검증 | ![React Hook Form](https://img.shields.io/badge/React%20Hook%20Form-EC5990?style=for-the-badge&logo=reacthookform&logoColor=white) ![Zod](https://img.shields.io/badge/Zod-3E67B1?style=for-the-badge&logo=zod&logoColor=white) |
| 지도 | ![Kakao Maps](https://img.shields.io/badge/Kakao%20Maps%20SDK-FFCD00?style=for-the-badge&logo=kakao&logoColor=black) |
| API 목업 / 테스트 | ![MSW](https://img.shields.io/badge/MSW-FF6A33?style=for-the-badge) ![Playwright](https://img.shields.io/badge/Playwright-2EAD33?style=for-the-badge&logo=playwright&logoColor=white) |
| 배포 | ![Vercel](https://img.shields.io/badge/Vercel-000000?style=for-the-badge&logo=vercel&logoColor=white) |

**왜 pgvector인가**: 별도 Vector DB(Chroma 등)를 구축하지 않고 PostgreSQL의 pgvector 확장으로 RDB와 Vector DB를 하나로 통합 — 인프라를 단순화하고 팀 규모/예산(7주, 약 48만원)에 맞춘 선택

---

## 4. 시스템 아키텍처 (설계)

<img width="1920" height="1080" alt="시스템 아키텍처" src="https://github.com/user-attachments/assets/50606b7b-5ffd-4dca-9930-56f825f04e63" />

```
[Next.js/Vercel] --HTTPS--> [Nginx(EC2)] --> [Spring Boot] --+--> [RDS: PostgreSQL+pgvector]
                                                              +--> [Redis: 캐싱/토큰 블랙리스트]
                                                              +--> [AWS Bedrock: LLM 응답 생성]
                                                              +--> [Kakao Maps API 연동은 FE에서 직접 호출]
```

- 사용자 질문 → 백엔드가 pgvector로 유사 FAQ 검색(RAG) → 검색 결과를 근거로 Bedrock 호출 → 자연어 답변 생성 → 채팅 세션에 저장
- 위치 질의 시 매장 좌표 기반 거리 계산 → 채팅 응답과 함께 지도 표시용 데이터 반환
- 인프라는 AWS EC2 단일 인스턴스(dev/prod 포트 분리) + RDS + Nginx/Let's Encrypt HTTPS, GitHub Actions로 배포 자동화

---

## 5. ERD (설계)

<img width="1328" height="803" alt="image" src="https://github.com/user-attachments/assets/a0d7705c-194d-446b-801a-227043c1bfed" />


| 테이블 | 설명 |
|---|---|
| `users` | 회원 정보. `email`/`password_hash`는 nullable(소셜 전용 계정 대응), PII(email/name/phone)는 마스킹 대상 |
| `user_oauths` | 소셜 로그인 연동(LOCAL/GOOGLE/KAKAO/NAVER), 한 사용자가 여러 소셜 계정 연결 가능 |
| `faqs` | FAQ 본문 + `embedding vector(768)`(intfloat/multilingual-e5-base 기준). 삭제는 `status`를 `INACTIVE`로 바꾸는 소프트 삭제이며, RAG 검색은 `status='ACTIVE'`만 대상 |
| `stores` | 매장 정보(좌표, 영업시간, 연락처) |
| `chat_sessions` / `chat_messages` | 대화 세션과 메시지. `chat_messages.status`로 생성중/완료/실패/재시도 상태 관리, `latency_ms`로 응답 시간 기록 |
| `chat_message_faq_refs` | assistant 메시지가 답변 근거로 사용한 FAQ 매핑(N:M, JSON 배열 대신 정규화) |
| `store_reservations` | (선택, 추후 확장) 매장 방문 예약 |

**설계 원칙**
- Vector DB를 따로 두지 않고 `faqs.embedding` 컬럼(pgvector)으로 RDB와 통합
- FAQ ~1,000건 규모에서는 pgvector 인덱스(IVFFlat/HNSW) 없이 Exact Search로 충분하다고 판단, 필요 시 추후 추가
- PII 컬럼(email/name/phone)은 로그·API 응답 양쪽에서 마스킹 처리 원칙
- 테이블명은 전부 복수형(`users`만 PostgreSQL 예약어 회피 목적으로 원래도 복수형)

---

## 6. 기능 및 API 설계

### 전체 기능 목록

| 구분 | 기능 | 우선순위 |
|---|---|---|
| 인증 | 회원가입 / 로그인 / 마이페이지 조회·수정 | 필수 |
| 채팅 | AI 질문-답변(RAG) / 응답 상태 처리 / 세션·히스토리 저장 | 필수 |
| 채팅 | 답변 피드백(👍/👎) | 선택 |
| 매장 | 가까운 매장 안내 / 주변 매장 목록 조회 | 필수 |
| 매장 | 매장 예약 | 선택(추후) |
| 관리자 | FAQ 관리 CRUD / 매장 관리 CRUD | 필수 |
| 관리자 | 질문 로그·통계 대시보드 | 선택 |

### API 엔드포인트 설계

공통: 응답은 wrapper 없이 DTO를 최상위로 반환(성공은 body 그대로, 실패는 `{code, message}`), 생성은 `201` 나머지는 `200`+body(`204` 미사용), 페이징은 `page/size/keyword/sortBy` 공통 파라미터.

**Auth**

| Method | Path | 설명 |
|---|---|---|
| POST | `/auth/signup` | 회원가입 |
| POST | `/auth/login` | 로그인 (Access/Refresh 토큰 발급) |
| POST | `/auth/refresh` | 토큰 재발급 (Refresh Token rotation) |
| POST | `/auth/logout` | 로그아웃 |

**User**

| Method | Path | 설명 |
|---|---|---|
| GET | `/users/me` | 내 정보 조회 |
| PATCH | `/users/me` | 내 정보 수정 |

**Chat**

| Method | Path | 설명 |
|---|---|---|
| GET | `/chat/sessions` | 세션 목록 조회 |
| POST | `/chat/sessions` | 새 세션 생성 |
| GET | `/chat/sessions/{id}/messages` | 세션 내 메시지 조회 |
| POST | `/chat/sessions/{id}/messages` | 질문 전송 → RAG 검색 → LLM 응답 생성 |
| POST | `/chat/messages/{id}/feedback` | 답변 피드백(선택) |

**Store**

| Method | Path | 설명 |
|---|---|---|
| GET | `/stores/nearest` | 가장 가까운 매장 조회 |
| GET | `/stores/nearby` | 반경 내 매장 목록 조회 |
| POST | `/stores/{id}/reservations` | 매장 예약(선택, 추후 설계) |

**Admin**

| Method | Path | 설명 |
|---|---|---|
| GET/POST/PATCH/DELETE | `/admin/faqs` | FAQ 관리 (대량 등록은 API가 아닌 별도 시드 스크립트로) |
| GET/POST/PATCH/DELETE | `/admin/stores` | 매장 관리 |
| GET | `/admin/stats/chat` | 질문/피드백 통계(선택) |

> 전체 요청/응답 스키마는 [`docs/04_API명세서.md`](../docs/04_API명세서.md) 참고.

---

## 7. 핵심 설계 포인트

**RAG 응답 생성 흐름**
1. 사용자 질문을 임베딩으로 변환
2. pgvector로 유사 FAQ Top-K 검색
3. 유사도가 임계값 미만이면 "관련 정보 없음"으로 폴백(고객센터 안내)
4. 검색된 FAQ + 최근 대화 N턴을 프롬프트로 구성해 Bedrock 호출
5. 생성된 응답을 세션에 저장, 근거 FAQ는 `chat_message_faq_refs`로 매핑

**LLM 추상화**: 향후 다른 LLM 제공자로 교체 가능하도록 Provider 인터페이스로 감싸는 구조 원칙 유지(NFR-EXT01)

**인증**: JWT 기반, Access Token(짧은 만료) + Refresh Token(Redis 저장·rotation), 토큰 저장 방식(HttpOnly Cookie vs body)은 BE1-FE1 협의 예정

**FAQ 소프트 삭제**: 하드 삭제 대신 `status=INACTIVE` 전환, 임베딩은 보존하되 RAG 검색 대상에서 제외

**응답 상태 관리**: LLM 호출은 `PENDING → COMPLETED / FAILED / RETRYING` 상태로 관리해 프론트가 로딩/실패/재시도 UI를 그릴 수 있게 설계

**보안 원칙**: PII 마스킹(응답/로그), 관리자 API는 역할(role=ADMIN) 미들웨어 검증, DB 자격증명은 환경변수로만 주입, 네이티브 쿼리는 파라미터 바인딩

---

## 8. 현재 구현 상태

Phase 1 초반 기준, 나머지는 위 설계대로 진행 예정입니다.

| 영역 | 상태 |
|---|---|
| 공통 기반(Docker/DB/AWS/CI-CD/HTTPS) | 완료 |
| 회원가입/로그인/JWT 인증 | 완료 |
| 매장 CRUD·위치 기반 검색 | 완료 |
| FAQ 데이터 적재·임베딩 파이프라인 | 초기 구현(테스트 데이터 일부) |
| RAG 검색(pgvector 유사도) | 키워드 폴백 검색 + 유사도 쿼리 구현, 실 데이터 대기 |
| LLM 연동·Chat API | 설계 단계 |
| 프론트 전반 | 진행중 |

---

## 9. 남은 로드맵

- Phase 1: FAQ 1,000개 데이터 생성·적재, Chat API 구현, 매장 예약(선택) 검토
- Phase 2: 전체 도메인 통합 테스트, 관리자 통계 API(선택), Redis 캐싱·토큰 블랙리스트 적용, CI/CD 고도화
- Phase 3: 예외 처리 강화, 성능 점검, 배포 안정화, (선택) Prometheus/Grafana 모니터링

---

> **1조 : 500 Team**
