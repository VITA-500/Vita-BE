# 검색 평가 도구와 공통 검색 파이프라인 사용 안내

RAG 검색(FAQ·요금제)의 품질을 숫자로 재고, 영어 임베딩·질문 변환·Hybrid 같은 개선 실험을 같은 기준으로 비교하기 위한 도구와 구조를 설명한다. 대상 독자는 실험 B(영어, BE1), 실험 C(질문 변환, BE4), Hybrid(BE6)를 맡은 팀원이다.

## 1. 한눈에 보기

- **검색 파이프라인**(`com.vita.search.pipeline.RetrievalPipeline`)은 실제 서비스와 평가 도구가 함께 쓰는 검색 경로다. 검색 로직을 바꾸면 서비스와 평가가 같이 바뀐다.
- **평가 러너**(`RetrievalEvalRunner`)는 평가셋 200문항(`data/regression/eval_v2/retrieval_eval_v2.jsonl`)으로 이 파이프라인을 돌려 Recall·nDCG·MRR·Hit 같은 지표와 단계별 응답 시간을 낸다.
- 새 실험은 파이프라인의 **교체 지점 두 곳**(질문 변환, FAQ 후보 검색)에 구현을 하나 끼우고 설정 한 줄만 바꾸면 측정된다. 러너 코드를 고치지 않는다.
- BE4가 부르는 `FaqRetrievalService.search(query, topK)`는 바뀌지 않았다.

## 2. 파이프라인 구조

| 단계 | 하는 일 | 인터페이스 | 기본 구현 | 끼우는 곳 |
| --- | --- | --- | --- | --- |
| 1. 질문 변환 | 원 질문을 FAQ 검색용·요금제 검색용 질문으로 바꾼다 | `QueryTransformer` | `IdentityQueryTransformer`(변환 없음) | BE4 질문 변환, BE1 영어 번역 |
| 2. 임베딩 | 변환된 질문을 벡터로 바꾼다 | `EmbeddingProvider`(기존) | e5 임베딩 서버 | |
| 3. FAQ 후보 검색 | 질문에 가까운 FAQ 후보를 순위대로 가져온다 | `FaqRetriever` | `VectorFaqRetriever`(한국어 컬럼 pgvector) | BE1 영어 컬럼 검색, BE6 Hybrid |
| 4. 요금제 검색 | 벡터 유사도와 가격·데이터량 등 조건 매칭 | `PlanSearchService`(기존) | | |
| 5. Context 구성 | 무관 질문 규칙 → 분류 이름 가산점 → 같은 답변 중복 제거 → topK → threshold → 응답 변환 | `RetrievalPipeline.selectFaq` | 모든 변형이 공통 사용 | |

흐름: `질문 → [1 변환] → [2 임베딩] → [3 후보 검색(풀 30)] → [4 요금제 검색] → [5 Context 구성] → FaqRetrievalContext`

- 후보 풀(기본 30개)은 최종 개수(topK)와 별개다. 같은 답변의 원문·변형이 자리를 다 차지하지 않도록 넉넉히 가져와 5단계에서 줄인다.
- 5단계(후처리)를 모든 변형이 같은 코드로 거치므로, 변형 간 점수 차이는 1~3단계 차이에서만 나온다.

## 3. 평가 러너 실행

### 3-1. 준비

1. 로컬 도커에서 `vita-postgres-local`, `vita-redis-local`, `vita-embedding-local`을 켠다. **`vita-backend-local`은 켜지 않는다.** 옛 이미지가 옛 FAQ를 다시 적재한다.
2. 새 FAQ 데이터(`uplus_faq_all_cleaned.jsonl`, 1,052건)를 DB에 적재하고 임베딩해 둔다. 평가셋의 정답 FAQ가 DB에 없으면 러너가 점수를 내지 않고 바로 중단한다.

### 3-2. 실행

```bash
SEARCH_EVAL_ENABLED=true ./gradlew bootRun
```

러너는 끝나도 서버가 종료되지 않는다. 로그에 `RETRIEVAL EVAL DONE`이 나오면 서버를 직접 종료한다.

### 3-3. 설정(환경변수)

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `SEARCH_EVAL_ENABLED` | false | true일 때만 러너가 동작한다 |
| `SEARCH_EVAL_TOP_K` | 10 | 최종 결과 개수 K. 서비스의 topK와 같게 둔다 |
| `SEARCH_EVAL_POOL_SIZES` | 10,20,30,50 | 비교할 후보 풀 크기 |
| `SEARCH_EVAL_MAIN_POOL_SIZE` | 30 | 말투별·안정성·약한 질문 분석에 쓰는 대표 풀 크기 |
| `SEARCH_EVAL_QUERY_TRANSFORMER` | identityQueryTransformer | 평가에 쓸 질문 변환기의 빈 이름 |
| `SEARCH_EVAL_FAQ_RETRIEVER` | vectorFaqRetriever | 평가에 쓸 FAQ 후보 검색기의 빈 이름 |
| `SEARCH_EVAL_TIMING_REPEATS` | 3 | 단계별 시간을 질문마다 몇 번 반복해 잴지(0이면 건너뜀) |
| `SEARCH_EVAL_TIMING_WARMUP` | 5 | 시간 측정 전에 결과를 버리고 먼저 돌려 둘 횟수 |
| `SEARCH_EVAL_RESOURCE` | classpath의 평가셋 | 다른 평가셋 파일을 쓸 때 |

임계값과 가산점은 서비스와 같은 설정(`retrieval.similarity-threshold` 0.83, `retrieval.category-boost.bonus` 0.01)을 그대로 쓴다. 비교 실험은 이 값을 바꾸지 않는다(기록 규칙 2).

### 3-4. 결과

러너는 로그에 요약을 출력하고 `build/regression-report/`에 CSV를 저장한다.

- `retrieval-eval-*.csv`: 질문별·풀 크기별 점수와 상위 K개 FAQ 목록
- `retrieval-eval-timing-*.csv`: 단계별 시간(평균, p95)

로그 구성: 풀 크기별 지표, 말투별 지표, 질문 수별 안정성, 약한 질문 목록, 단계별 응답 시간.
CSV는 슬랙에서 열면 한글이 깨질 수 있으니 팀에 공유할 때는 엑셀(xlsx)로 변환해서 올린다.

### 3-5. 지표 읽는 법

| 지표 | 뜻 | 정답 판정 |
| --- | --- | --- |
| 풀 Recall | 후보 풀 안에 정답 FAQ가 몇 % 들어왔나(FAQ ID 기준) | 관련도 1점 이상 |
| 최종 Recall@K | 최종 상위 K개에 정답 답변 묶음이 몇 % 들어왔나 | 1점 이상, 2점 이상 둘 다 |
| nDCG@K | 정답이 위쪽에 있을수록 높은 점수(관련도 3/2/1 반영) | 관련도 그대로 |
| MRR | 첫 정답의 순위의 역수 | 관련도 2점 이상 |
| Hit@1, Hit@K | 상위 1개 / K개 안에 정답이 하나라도 있나 | 관련도 2점 이상 |
| P@K | 상위 K개 중 정답 비율 | 관련도 2점 이상 |

- 정답은 "답변 묶음" 단위다. 원문과 변형(-P1)은 같은 답변이라 한 묶음이며, 둘 중 하나만 가져와도 찾은 것으로 센다.
- K=10에서는 정답 묶음이 평균 5.6개뿐이라 P@K의 이론 최대가 낮다. **Recall과 nDCG를 주 지표**로 본다.
- 점수는 정답지 검수 전 잠정값이다. 검수가 끝나면 Baseline(실험 A)을 재측정하고 모든 실험의 기준을 새 값으로 바꾼다.

## 4. 새 변형 연결하기

### 4-1. 순서

1. `QueryTransformer` 또는 `FaqRetriever`를 구현한 클래스를 만들고 `@Component("빈이름")`으로 등록한다.
2. 환경변수로 그 빈 이름을 지정해 러너를 실행한다.
3. 결과(로그와 CSV)를 `버전 별 테스트 기록 양식`에 옮겨 적는다. 바꾼 변수는 한 번에 하나만 바꾼다.

예) 질문 변환기를 만든 경우:

```bash
SEARCH_EVAL_ENABLED=true SEARCH_EVAL_QUERY_TRANSFORMER=myQueryTransformer ./gradlew bootRun
```

서비스에 적용할 때는 `SEARCH_PIPELINE_QUERY_TRANSFORMER`, `SEARCH_PIPELINE_FAQ_RETRIEVER`에 같은 이름을 지정한다. 빈 이름이 틀리면 조용히 기본값으로 넘어가지 않고 사용 가능한 이름 목록과 함께 기동이 실패한다.

### 4-2. 질문 변환기 (`QueryTransformer`)

```java
@Component("myQueryTransformer")
public class MyQueryTransformer implements QueryTransformer {

	@Override
	public TransformedQuery transform(String query) {
		String rewritten = ...; // 번역 또는 LLM 재작성
		return new TransformedQuery(query, rewritten, query);
	}
}
```

- `TransformedQuery(original, faqQuery, planQuery)`: FAQ 검색에 쓸 질문과 요금제 검색에 쓸 질문을 따로 돌려준다.
- **`original`은 항상 사용자 원문**이다. 무관 질문 규칙과 분류 이름 가산점은 한국어 원문 기준이라 변환된 질문으로는 맞지 않는다.
- **`planQuery`는 한국어여야 한다.** 요금제 조건 추출기(가격·데이터량·대상 그룹)가 한국어 문장을 읽는다. 영어 실험에서 요금제 쪽을 바꾸지 않을 거면 원문을 그대로 넣는다.
- LLM을 부르는 변환기는 실패해도 검색이 죽지 않도록 `TransformedQuery.unchanged(query)`로 되돌려 주는 것을 권장한다.
- 이 단계 시간은 "질문 변환" 시간으로 따로 잰다. 변환 LLM 호출 시간이 거기에 잡힌다.

### 4-3. FAQ 후보 검색기 (`FaqRetriever`)

```java
@Component("myFaqRetriever")
public class MyFaqRetriever implements FaqRetriever {

	@Override
	public List<FaqSimilarityResult> retrieve(RetrievalQuery query, int poolSize) {
		// query.text(): 질문 텍스트, query.vector(): 그 임베딩 벡터
		...
	}
}
```

구현체가 지킬 약속은 세 가지다.

1. **후보 순서는 그 검색 방식의 최종 순위**다. 가장 관련 있는 후보가 앞에 온다. 후처리는 이 순서를 입력으로 받는다.
2. **`similarity`는 항상 질문 벡터와 문서 벡터의 코사인 유사도(0~1)**다. threshold 판정과 BE4에 알리는 `topSimilarity`가 이 값을 쓴다. Hybrid의 RRF 점수처럼 단위가 다른 점수를 `similarity`에 넣지 않는다. 키워드 검색으로만 올라온 후보도 코사인 유사도를 계산해 채운다.
3. **`id`는 원본 FAQ의 id(`faqs.id`)**다. 영어 테이블(`faqs_en`)에서 찾았더라도 원본 FAQ id를 돌려줘야 같은 정답지로 채점된다. FE1에 표시되는 `category/subcategory/question/answer`는 원본(한국어) 값을 채우는 것을 권장한다.

후보는 `poolSize`개까지 돌려준다(기본 30). 평가 러너는 풀 크기를 10/20/30/50으로 바꿔 비교하려고 가장 큰 풀(50)을 한 번 가져온 뒤 앞에서 잘라 쓴다. 순위 순으로 돌려주기만 하면 된다.

### 4-4. 실험별 연결 예

| 실험 | 질문 변환기 | FAQ 후보 검색기 | 비고 |
| --- | --- | --- | --- |
| A. Baseline | 기본(변환 없음) | 기본(한국어 벡터) | 현재 기준점 |
| B. 영어만 | 영어 번역 변환기(BE1) | 영어 컬럼 검색기(BE1, `faqs_en`) | 번역·영어 검색은 한 묶음으로 쓰이므로 "언어" 한 변수로 본다 |
| C. 질문 변환만 | 질문 재작성 변환기(BE4) | 기본 | |
| D. 영어 + 질문 변환 | 재작성 + 번역 | 영어 컬럼 검색기 | |
| A+H, B+H, C+H, D+H | 위와 같음 | Hybrid 검색기(BE6) | 각 조합의 후보 검색기를 Hybrid로 교체 |

## 5. BE4 Trace/Span에 넘길 검색 단계 정보

`RetrievalPipeline.run()`이 돌려주는 `RetrievalResult`에 검색 단계의 모든 정보가 들어 있다. 서비스 경로(`FaqRetrievalServiceImpl`)는 이 중 `context()`만 반환한다. BE4가 Trace에 검색 단계를 남기려면 파이프라인을 직접 호출해 아래 필드를 쓰면 된다.

| 필드 | 내용 |
| --- | --- |
| `query()` | 원 질문과 FAQ·요금제 검색에 쓴 질문 |
| `faq().pool()` | 후보 풀(순위와 유사도) |
| `faq().candidates()` | 가산점·중복 제거·topK를 거친 후보(threshold 적용 전) |
| `faq().results()` | threshold를 통과한 최종 FAQ |
| `faq().topSimilarity()` | 풀 전체의 최고 유사도(threshold 미달이어도 채워진다) |
| `planOutcome()`, `planResults()` | 요금제 후보, 조건 매칭 여부, 최종 요금제 |
| `irrelevantRules()` | 걸린 무관 질문 규칙(비어 있지 않으면 컨텍스트가 비워진다) |
| `timings()` | 질문 변환 / 임베딩 / FAQ 검색 / 요금제 검색 / Context 구성 시간(나노초) |
| `topSimilarity()` | BE4에 알리는 최고 유사도 |

- threshold는 `RetrievalSettings`(`pipeline.settings()`)에서 읽을 수 있다.
- 검색 한 번의 단계별 시간은 INFO 로그에도 한 줄 남는다(`검색 단계별 시간: ...`).

## 6. 현재 측정 결과(Baseline, 정답지 검수 전 잠정)

평가셋 v2.1 200문항, 풀 30, K=10, 한국어 원 질문 + 현재 임베딩 + 현재 벡터 검색.

| 지표 | 값 |
| --- | --- |
| 최종 Recall@10 (1점↑ / 2점↑) | 66.8% / 77.7% |
| nDCG@10 | 74.4% |
| MRR / Hit@1 / Hit@10 | 93.2% / 89.5% / 98.5% |

단계별 응답 시간(서비스와 같은 설정, 로컬 도커, 질문 200개 × 3회):

| 단계 | 평균(ms) | p95(ms) |
| --- | --- | --- |
| 질문 변환 | 0.0 | 0.0 |
| 임베딩 | 38.5 | 67.5 |
| FAQ 검색 | 15.3 | 22.4 |
| 요금제 검색 | 3.7 | 9.7 |
| Context 구성 | 0.1 | 0.2 |
| 전체 | 57.7 | 94.5 |

## 7. 알려진 한계

- 평가셋은 FAQ 질문 200개뿐이다. 요금제 평가셋(`plan_eval_v1.jsonl`, 130문항)은 정답지 검수 중이며, 요금제용 러너는 아직 없다. 요금제 지표와 요금제 쪽 변형 평가는 미측정이다.
- 영어 컬럼 검색 변형은 FAQ에만 해당한다. 요금제(`plans_en`)는 파이프라인에서 FAQ와 같은 구조로 교체하지 않는다(요금제는 15종이라 우선순위가 낮다).
- 정답지는 LLM이 만든 초안이고 팀원 검수가 진행 중이다. 점수는 검수 후 달라질 수 있다.
- 단계별 시간은 로컬 도커 기준이다. 서버(EC2)·네트워크 환경의 시간은 따로 재야 한다.
- LLM 단계(질문 변환 호출, 답변 생성)의 시간은 해당 구현을 끼운 뒤 측정된다. 답변 생성 시간은 BE4가 측정 코드를 넣는다.
