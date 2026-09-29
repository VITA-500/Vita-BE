CREATE TABLE unresolved_question_reports (
    id            BIGSERIAL PRIMARY KEY,
    message_id    BIGINT      NOT NULL REFERENCES chat_messages(id),
    user_id       BIGINT      NOT NULL,
    question_text TEXT,
    status        VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at    TIMESTAMP   NOT NULL,
    CONSTRAINT uq_report_message_user UNIQUE (message_id, user_id)
);