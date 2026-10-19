-- 질문 1건의 처리 과정(Trace)과 그 안의 단계(Span)를 그 순간의 원문 그대로 기록하는 로그 테이블.
-- faqs, plans, chat_messages에는 FK를 걸지 않는다. FAQ·요금제는 수정될 수 있어서 id가 아니라 원문을 history에 복사해 둔다.
-- message_id는 답변 메시지를 찾기 위한 값일 뿐이다. 한 번 쓴 기록은 수정하지 않고 추가만 한다.

CREATE TABLE traces (
    id           BIGSERIAL PRIMARY KEY,
    message_id   BIGINT      NOT NULL,                 -- 답변 메시지 id (FK 없음, 찾기용)
    app_version  VARCHAR(40),                          -- 이 trace를 만든 앱 버전(git 커밋)
    config       JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- 그 시점의 설정 스냅샷(임계값·모델·프롬프트 해시 등)
    created_at   TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_traces_message ON traces (message_id);
CREATE INDEX idx_traces_created ON traces (created_at);

CREATE TABLE spans (
    id          BIGSERIAL PRIMARY KEY,
    trace_id    BIGINT      NOT NULL REFERENCES traces (id) ON DELETE CASCADE,  -- 같은 로그 안이라 FK를 건다
    type        VARCHAR(30) NOT NULL,  -- transform / faq_retrieve / plan_retrieve / plan_lookup / store_lookup / generation
    started_at  TIMESTAMP   NOT NULL,
    ended_at    TIMESTAMP   NOT NULL,
    history     JSONB       NOT NULL   -- 단계별 원문 스냅샷. 오류는 error 필드에 남긴다
);

CREATE INDEX idx_spans_trace ON spans (trace_id, id);
