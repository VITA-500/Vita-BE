ALTER TABLE users
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'));

CREATE INDEX idx_users_status_role ON users(status, role);

-- A transaction-scoped row lock serializes admin changes, including last-admin checks.
CREATE TABLE user_management_lock (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO user_management_lock(id) VALUES (1);

CREATE TABLE user_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT NOT NULL REFERENCES users(id),
    target_id BIGINT NOT NULL REFERENCES users(id),
    action VARCHAR(30) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_fields VARCHAR(100) NOT NULL,
    before_state VARCHAR(100),
    after_state VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_audit_logs_target ON user_audit_logs(target_id, id DESC);
