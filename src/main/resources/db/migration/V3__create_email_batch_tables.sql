-- Emails drafted by the company assistant, waiting for a human to approve them before they are sent.
CREATE TABLE EMAIL_BATCH (
    batch_id     UUID            NOT NULL,
    company_id   BIGINT          NOT NULL,
    prompt       VARCHAR(8000)   NOT NULL,
    status       VARCHAR(20)     NOT NULL,
    created_at   TIMESTAMP       NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMP       NOT NULL,
    approved_at  TIMESTAMP       NULL,
    CONSTRAINT pk_email_batch PRIMARY KEY (batch_id)
);

CREATE TABLE EMAIL_DRAFT (
    draft_id        UUID            NOT NULL,
    batch_id        UUID            NOT NULL,
    customer_pk     UUID            NOT NULL,
    customer_id     BIGINT          NOT NULL,
    recipient_name  VARCHAR(255)    NOT NULL,
    recipient_email VARCHAR(255)    NOT NULL,
    language        VARCHAR(50)     NOT NULL,
    subject         VARCHAR(255)    NOT NULL,
    body            VARCHAR(10000)  NOT NULL,
    status          VARCHAR(10)     NOT NULL,
    error           VARCHAR(500)    NULL,
    message_id      VARCHAR(255)    NULL,
    sent_at         TIMESTAMP       NULL,
    CONSTRAINT pk_email_draft          PRIMARY KEY (draft_id),
    CONSTRAINT fk_email_draft_batch    FOREIGN KEY (batch_id)    REFERENCES EMAIL_BATCH (batch_id),
    CONSTRAINT fk_email_draft_customer FOREIGN KEY (customer_pk) REFERENCES CUSTOMER (customer_pk),
    CONSTRAINT uq_email_draft_customer UNIQUE (batch_id, customer_pk)
);

CREATE INDEX ix_email_batch_company ON EMAIL_BATCH (company_id);
