# 인증 API 변경 공유 (BE1)


소셜 로그인을 붙이면서 **인증 방식이 헤더 토큰에서 HttpOnly 쿠키로 바뀌었습니다.**
그 결과 CSRF 방어가 필요해져 활성화했고, **기존 프론트 코드가 그대로면 일부 요청이 403으로 막힙니다.**

먼저 읽어야 할 것은 두 가지입니다.

1. FE는 모든 요청에 `credentials: 'include'`를 켜야 합니다 (안 켜면 401)
2. FE는 POST/PATCH/DELETE에 CSRF 토큰 헤더를 붙여야 합니다 (안 붙이면 403)

---

## 1. 지금 당장 깨지는 것

CSRF 검증이 켜지면서, 아래 엔드포인트는 `X-XSRF-TOKEN` 헤더 없이는 **403**이 납니다.

| 메서드 | 경로 | 담당 |
|---|---|---|
| POST | `/chat/sessions` | BE4 |
| POST | `/admin/stores` | BE5 |
| PATCH | `/admin/stores/{storeId}` | BE5 |
| DELETE | `/admin/stores/{storeId}` | BE5 |

**GET 요청은 영향이 없습니다.** CSRF는 상태를 바꾸는 메서드만 검사합니다.
`/auth/**`는 면제라 로그인·회원가입·로그아웃은 헤더 없이 그대로 동작합니다.

앞으로 POST/PUT/PATCH/DELETE를 새로 추가하시면 **자동으로 CSRF 대상이 됩니다.**
백엔드에서 따로 하실 일은 없고, 프론트에서 헤더만 붙이면 됩니다.

---

## 2. FE 대응 방법

### 2-1. 쿠키를 주고받도록 설정 (필수)

토큰이 쿠키로 오가므로, 이 옵션이 없으면 **로그인해도 인증이 안 됩니다.**

```js
// axios — 한 번만 설정하면 전역 적용
axios.defaults.withCredentials = true;

// fetch — 요청마다
fetch(url, { credentials: 'include' })
```

### 2-2. CSRF 토큰 헤더 붙이기 (변경계열 요청)

서버가 `XSRF-TOKEN` 쿠키를 내려줍니다. 이 쿠키는 **JS에서 읽을 수 있게 열어뒀습니다**
(`withHttpOnlyFalse`). 값을 읽어 헤더에 그대로 넣으면 됩니다.

```js
// axios — 이 이름 그대로 쓰면 axios가 자동 처리합니다
axios.defaults.xsrfCookieName = 'XSRF-TOKEN';
axios.defaults.xsrfHeaderName = 'X-XSRF-TOKEN';
```

axios 기본값이 이미 이 이름이라, **대부분 위 두 줄 없이도 동작합니다.**
`withCredentials`만 켜면 끝날 가능성이 높습니다.

fetch를 쓰신다면 직접 넣어야 합니다.

```js
const csrf = document.cookie
  .split('; ')
  .find(c => c.startsWith('XSRF-TOKEN='))
  ?.split('=')[1];

fetch(url, {
  method: 'POST',
  credentials: 'include',
  headers: { 'X-XSRF-TOKEN': decodeURIComponent(csrf) },
});
```

> `XSRF-TOKEN` 쿠키는 서버가 응답할 때 발급합니다. 앱 첫 진입에서 GET 요청을
> 한 번이라도 보냈다면 쿠키가 있습니다.

### 2-3. 토큰을 직접 저장하던 코드 제거

**`localStorage`·`sessionStorage`에 토큰을 넣던 코드는 지워주세요.**
응답 body에 `accessToken`이 더 이상 오지 않습니다. 브라우저가 쿠키를 자동으로
실어 보내므로 FE가 토큰을 다룰 일 자체가 없어집니다.

`Authorization` 헤더를 수동으로 붙이던 인터셉터도 제거 대상입니다.

---

## 3. 응답 형태 변경

### 로그인 `POST /auth/login`

**이전**

```json
{ "accessToken": "eyJhbGci...", "user": { "userId": 1, ... } }
```

**현재** — 토큰이 body에서 빠지고 `Set-Cookie`로 내려갑니다

```json
{ "user": { "userId": 1, "email": "user@example.com", "name": "김어진", "role": "USER" } }
```

```
Set-Cookie: accessToken=eyJhbGci...; HttpOnly; Path=/; Max-Age=...
```

### 로그아웃 `POST /auth/logout` (신설)

인증 쿠키를 만료시킵니다. 요청 body 없고 응답 body도 없습니다(200).

```js
await axios.post('/auth/logout');
```

> 서버에 저장된 세션이 없어 쿠키 삭제가 곧 로그아웃입니다.
> 다만 **이미 발급된 토큰은 만료 전까지 기술적으로 유효합니다.**
> 토큰 블랙리스트(Redis)는 Phase 3 선택 항목으로 남아 있습니다.

### 내 정보 `GET /users/me`

명세서에 없던 필드 2개가 추가됐습니다. FE 화면 분기에 필요해서입니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `hasPassword` | boolean | `false`면 **비밀번호 변경 UI를 숨겨야 합니다** (소셜 전용 계정) |
| `linkedProviders` | string[] | 연결된 소셜 계정. 예: `["KAKAO"]`, 없으면 `[]` |

이메일·전화번호는 마스킹돼서 내려갑니다(`us**@example.com`, `010-****-5678`).
**카카오 가입자는 `email`이 `null`일 수 있습니다** — 카카오가 이메일 제공을
선택 동의로 두기 때문입니다. null 처리 부탁드립니다.

---

## 4. 소셜 로그인 연동

FE는 아래 주소로 **이동시키기만** 하면 됩니다. ajax로 호출하면 안 됩니다
(브라우저가 소셜 제공자 페이지로 리다이렉트돼야 합니다).

```
GET /oauth2/authorization/google
GET /oauth2/authorization/kakao
GET /oauth2/authorization/naver
```

```js
window.location.href = `${API_BASE}/oauth2/authorization/kakao`;
```

인증이 끝나면 서버가 토큰 쿠키를 심고 아래 주소로 리다이렉트합니다.

```
http://localhost:3000/oauth/callback
```

**이 콜백 경로는 현재 임시값입니다.** FE에서 쓰실 경로를 알려주시면 맞추겠습니다.
콜백 페이지에서는 **URL에서 토큰을 꺼낼 필요가 없습니다** — 이미 쿠키에 있으므로
`GET /users/me`를 호출해 로그인 상태를 확인하고 원하는 화면으로 보내시면 됩니다.

### 소셜 로그인 실패

실패 시에도 같은 콜백으로 리다이렉트되며, 쿼리에 에러 코드가 붙습니다.

| 코드 | 상황 | 권장 안내 |
|---|---|---|
| `OAUTH_EMAIL_CONFLICT` | 소셜 이메일이 기존 자체 가입 계정과 중복 | "이미 가입된 이메일입니다. 일반 로그인을 이용해주세요." |
| `OAUTH_AUTHENTICATION_FAILED` | 사용자가 동의 취소했거나 제공자 인증 실패 | 로그인 화면으로 복귀 |

> 이메일이 겹칠 때 **자동으로 계정을 연결하지 않습니다.** 자동 연결하면 공격자가
> 피해자 이메일로 소셜 계정을 만들어 기존 계정을 탈취할 수 있기 때문입니다.

---

## 5. BE 담당자에게

### 테이블명 확인 (BE 전원)

develop의 `V4__pluralize_table_names.sql`로 테이블명이 복수형이 됐습니다
(`user_oauth` → `user_oauths` 등). **로컬 DB에 마이그레이션을 적용하고 실행하세요.**
안 하면 엔티티 매핑이 실패합니다.

### 공개 경로 화이트리스트 (BE5)

`SecurityConfig`의 `PUBLIC_PATHS`가 `/stores/**`로 통합돼 있습니다.
`/admin/stores/**`는 그보다 먼저 `hasRole("ADMIN")` 규칙에 걸리도록 순서를
유지했으니, **관리자 API는 계속 보호됩니다.**

### 인증 정보 꺼내는 방법 (변경 없음)

기존과 동일합니다. 쿠키로 바뀐 건 토큰 전달 경로일 뿐, 컨트롤러에서 쓰는
방식은 그대로입니다.

```java
@GetMapping("/something")
public X get(@AuthenticationPrincipal UserPrincipal principal) {
    Long userId = principal.getUserId();
}
```

> `userId`를 요청 파라미터나 body로 받지 마세요. 남의 id를 넣어 조회하는
> 취약점이 생깁니다. 반드시 `principal`에서 꺼내야 합니다.

### Swagger에서 테스트할 때

헤더 방식도 계속 지원합니다. `JwtAuthenticationFilter`가 **쿠키를 먼저 보고,
없으면 `Authorization: Bearer` 헤더를 봅니다.** Swagger의 Authorize 버튼과
curl은 기존처럼 쓰시면 됩니다.

---

## 6. 배포 전 확인 필요 (인프라)

프론트(Vercel)와 백엔드(EC2) 도메인이 달라, 운영 환경에서는 쿠키 설정이
추가로 필요합니다.

```yaml
app:
  cookie:
    secure: true       # HTTPS 필수
    same-site: None    # 크로스 도메인 쿠키 전송 허용
```

`SameSite=None`은 브라우저가 `Secure`를 요구하므로 **HTTPS가 반드시 있어야
동작합니다.** 로컬은 http라 `Lax`로 두고 있습니다.

`CORS_ALLOWED_ORIGINS`에 배포된 프론트 주소도 추가해야 합니다.
현재 기본값은 `http://localhost:5173,http://localhost:3000`입니다.

---

## 요약 체크리스트

**FE**

- [ ] `withCredentials: true` / `credentials: 'include'` 전역 적용
- [ ] localStorage 토큰 저장·읽기 코드 제거
- [ ] `Authorization` 헤더 수동 주입 인터셉터 제거
- [ ] 로그인 응답에서 `accessToken` 참조하던 코드 제거
- [ ] POST/PATCH/DELETE에 CSRF 헤더 (axios면 대부분 자동)
- [ ] `/users/me`의 `email` null 처리, `hasPassword`로 비밀번호 UI 분기
- [ ] 소셜 로그인 콜백 경로 확정해서 BE1에 공유
- [ ] 로그아웃 시 `POST /auth/logout` 호출로 변경

**BE4 · BE5**

- [ ] 로컬 DB에 V4 마이그레이션 적용
- [ ] 변경계열 엔드포인트가 FE에서 정상 호출되는지 확인

질문이나 안 되는 부분 있으면 BE1에게 알려주세요.
