# Vita-BE

VITA 백엔드. 패키지 구조/코딩 컨벤션은 [../docs/08_개발표준.md] 기준.

이 초기 커밋에는 실제 도메인(auth/faq/search/chat/store) 로직이 없다. 대신 `common/`(공통 응답
포맷·예외 처리·페이징)과 `sample/`이라는 예시 도메인 하나로 **패키지 구조와 계층 패턴만** 보여준다.
각자 담당 도메인 폴더를 `sample/`과 같은 구조(entity/repository/controller/service/dto)로 만들어
채워나가면 된다.

## 시작하기

```bash
./gradlew compileJava   # 컴파일 확인
./gradlew test          # 테스트
./gradlew bootRun        # 로컬 Postgres가 떠 있어야 함
```

## 패키지 구조

```
com.vita/
├── VitaApplication.java
├── common/            공통 응답 포맷(ErrorResponse — 성공 응답은 wrapper 없이 DTO 그대로 반환),
│                       전역 예외 처리(BusinessException + GlobalExceptionHandler), 공통 엔티티
│                       (BaseTimeEntity), 공통 페이징(PageRequest/PageResponse, sortBy 화이트리스트)
└── sample/            구조 예시용 도메인 — entity/repository/controller/service/dto 전 계층 샘플
    ├── SampleItemNotFoundException.java   도메인 예외는 서브패키지 없이 도메인 루트에 위치
    ├── entity/SampleItem.java
    ├── repository/SampleItemRepository.java
    ├── service/SampleItemService.java
    ├── controller/SampleItemController.java
    └── dto/SampleItemResponse.java, SampleItemCreateRequest.java
```

`sample/`은 실제 기능 명세(docs/03~04)에 없는 예시용 CRUD(`/samples`)다. 각 담당자가 본인
도메인(`auth`, `faq`, `search`, `chat`, `store` 등)을 만들 때 이 폴더와 똑같은 형태로 만들고,
다 만들고 나면 `sample/`은 지워도 된다.
