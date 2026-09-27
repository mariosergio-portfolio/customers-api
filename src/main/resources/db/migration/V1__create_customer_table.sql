CREATE TABLE CUSTOMER (
    customer_pk     UUID            NOT NULL,
    import_id       UUID            NOT NULL,
    company_id      BIGINT          NOT NULL,
    id              BIGINT          NOT NULL,
    name            VARCHAR(255),
    email           VARCHAR(255),
    age             INTEGER,
    country         VARCHAR(100),
    phone           VARCHAR(50),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP(6)    NULL,
    CONSTRAINT pk_customer             PRIMARY KEY (customer_pk),
    CONSTRAINT uq_company_business_id  UNIQUE (company_id, id)
);
