CREATE TABLE disbursement (
    id                          VARCHAR(36) PRIMARY KEY,
    loan_id                     VARCHAR(64) NOT NULL UNIQUE,
    borrower_account            VARCHAR(32) NOT NULL,
    borrower_ifsc               VARCHAR(16),
    borrower_upi                VARCHAR(64),
    amount_paise                BIGINT NOT NULL CHECK (amount_paise > 0),
    status                      VARCHAR(24) NOT NULL,
    current_attempt_id          VARCHAR(36),
    idempotency_key             VARCHAR(128) UNIQUE,
    idempotency_request_hash    VARCHAR(64),
    idempotency_response        CLOB,
    next_action_at              TIMESTAMP,
    failure_reason              VARCHAR(64),
    version                     INT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_disbursement_status_action ON disbursement(status, next_action_at);

CREATE TABLE disbursement_attempt (
    id                  VARCHAR(36) PRIMARY KEY,
    disbursement_id     VARCHAR(36) NOT NULL REFERENCES disbursement(id),
    channel             VARCHAR(8) NOT NULL,
    attempt_number      INT NOT NULL,
    status              VARCHAR(24) NOT NULL,
    failure_reason      VARCHAR(64),
    channel_response    CLOB,
    poll_count          INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP
);

CREATE INDEX idx_attempt_disbursement ON disbursement_attempt(disbursement_id);
CREATE INDEX idx_attempt_status ON disbursement_attempt(status);
