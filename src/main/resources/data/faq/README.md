# FAQ 데이터 구축 계획

## 분류 및 목표 수량

| 번호 | Category | 주요 Subcategory 예시 | 중요도 | 목표 FAQ 수 |
| ---: | --- | --- | :---: | ---: |
| 1 | 가입/개통 | 신규가입, 개통, 본인인증, 기기변경 | 상 | 80 |
| 2 | 요금제 | 변경, 추천, 데이터 제공량, 5G/LTE | 상 | 110 |
| 3 | 요금/납부 | 요금조회, 자동이체, 미납, 청구서 | 상 | 90 |
| 4 | 데이터 | 사용량, 추가 데이터, 테더링, 속도제한 | 상 | 80 |
| 5 | 통화/문자 | 통화불가, 문자, 국제전화, 스팸 | 중 | 55 |
| 6 | 유심/eSIM | 발급, 재발급, 교체, 인식오류 | 상 | 80 |
| 7 | 번호이동 | 신청, 진행, 취소, 조건 | 중 | 50 |
| 8 | 로밍 | 신청, 요금, 데이터, 국가, 해지 | 상 | 85 |
| 9 | 결합상품 | 가족결합, 인터넷결합, 할인 | 중 | 65 |
| 10 | 멤버십 | 등급, 혜택, 사용처, 포인트 | 중 | 55 |
| 11 | 분실/파손 | 분실신고, 정지, 보험, 파손 | 중 | 50 |
| 12 | 정지/해지 | 일시정지, 정지해제, 해지, 위약금 | 상 | 65 |
| 13 | 고객정보 변경 | 이름, 주소, 연락처, 명의 | 중 | 45 |
| 14 | 장애/네트워크 | 데이터불가, 통화불가, 속도저하 | 상 | 90 |
| 15 | 앱/웹 서비스 | 로그인, 인증, 앱오류, 마이페이지 | 중 | 60 |
|  | **합계** |  |  | **1,060** |

초기 생성 후 중복, 근거 부족, 복합 의도 FAQ를 정제해 최종 1,000건 이상을 확보한다.

## 검증 정책

- Category는 `FaqTaxonomy`에 등록된 위 15개 값만 허용한다.
- Subcategory는 위 표의 예시에 한정하지 않는다. 정책 및 실제 문의 유형에 따라 추가할 수 있다.
- Category와 Subcategory는 모두 비어 있을 수 없다.
- FAQ 질문과 답변은 한 가지 사용자 의도를 다룬다.
- 모든 FAQ는 하나 이상의 `source_policy_ids`를 가져야 한다.
- 카테고리별 JSONL 파일을 분리해 생성하고 `FAQ_IMPORT_RESOURCE`로 선택하여 적재한다.

## 파일명 예시

```text
cleaned/faq_signup_test.jsonl
cleaned/faq_plan_test.jsonl
cleaned/faq_billing_test.jsonl
cleaned/faq_data_test.jsonl
cleaned/faq_call_message_test.jsonl
cleaned/faq_usim_esim_test.jsonl
cleaned/faq_number_portability_test.jsonl
cleaned/faq_roaming_test.jsonl
cleaned/faq_bundle_test.jsonl
cleaned/faq_membership_test.jsonl
cleaned/faq_loss_damage_test.jsonl
cleaned/faq_suspension_termination_test.jsonl
cleaned/faq_customer_info_test.jsonl
cleaned/faq_network_issue_test.jsonl
cleaned/faq_app_web_test.jsonl
```
