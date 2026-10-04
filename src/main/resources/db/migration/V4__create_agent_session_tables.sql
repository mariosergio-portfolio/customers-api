-- Conversation memory of the company agent: a session is a series of prompts and answers about one company,
-- plus a pointer to the email batch the user is currently refining.
CREATE TABLE AGENT_SESSION (
    session_id  UUID       NOT NULL,
    company_id  BIGINT     NOT NULL,
    batch_id    UUID       NULL,
    created_at  TIMESTAMP  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agent_session PRIMARY KEY (session_id)
);

CREATE TABLE AGENT_TURN (
    turn_id     UUID            NOT NULL,
    session_id  UUID            NOT NULL,
    seq         INTEGER         NOT NULL,
    role        VARCHAR(10)     NOT NULL,
    text        VARCHAR(10000)  NOT NULL,
    CONSTRAINT pk_agent_turn         PRIMARY KEY (turn_id),
    CONSTRAINT fk_agent_turn_session FOREIGN KEY (session_id) REFERENCES AGENT_SESSION (session_id),
    CONSTRAINT uq_agent_turn_seq     UNIQUE (session_id, seq)
);
