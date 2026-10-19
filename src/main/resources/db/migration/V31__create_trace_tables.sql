-- 질문 1건의 처리 과정(Trace)과 그 안의 단계(Span)를 그 순간의 원문 그대로 기록하는 로그 테이블.
-- faqs, plans, chat_messages에는 FK를 걸지 않는다. FAQ·요금제는 수정될 수 있어서 id가 아니라 원문을 history에 복사해 둔다.
-- message_id는 답변 메시지를 찾기 위한 값일 뿐이다. 한 번 쓴 기록은 수정하지 않고 추가만 한다.

CREATE TABLE traces (
    id           BIGSERIAL PRIMARY KEY,
    message_id   BIGINT      NOT NULL,                 -- 답변 메시지 id (FK 없음, 찾기용)
    app_version  VARCHAR(40),                          -- 이 trace를 만든 앱 버전(git 커밋)
    config       JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- 그 시점의 설정 스냅샷(임계값·모델·프롬프트 해시 등)
    created_at   TIMESTAMP   NOT NULL DEFAULT now(),
    -- 목록 조회용 요약. 저장할 때 단계 기록에서 뽑아 함께 넣는다(목록·정렬·검색마다 단계 history를 풀어 보지 않으려고).
    question          TEXT,                              -- 사용자 질문 원문(조회 응답에서 마스킹)
    unresolved_reason VARCHAR(30),                       -- 미해결 사유. 답변이 실패했으면 ANSWER_FAILED
    total_ms          INTEGER,                           -- 첫 단계 시작부터 마지막 단계 종료까지(밀리초)
    has_error         BOOLEAN     NOT NULL DEFAULT FALSE -- 오류가 기록된 단계가 있는지
);

CREATE INDEX idx_traces_message ON traces (message_id);
CREATE INDEX idx_traces_created ON traces (created_at);
CREATE INDEX idx_traces_unresolved ON traces (unresolved_reason, created_at);

CREATE TABLE spans (
    id          BIGSERIAL PRIMARY KEY,
    trace_id    BIGINT      NOT NULL REFERENCES traces (id) ON DELETE CASCADE,  -- 같은 로그 안이라 FK를 건다
    type        VARCHAR(30) NOT NULL,  -- transform / faq_retrieve / plan_retrieve / plan_lookup / store_lookup / generation
    started_at  TIMESTAMP   NOT NULL,
    ended_at    TIMESTAMP   NOT NULL,
    history     JSONB       NOT NULL   -- 단계별 원문 스냅샷. 오류는 error 필드에 남긴다
);

CREATE INDEX idx_spans_trace ON spans (trace_id, id);
