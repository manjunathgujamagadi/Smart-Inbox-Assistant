-- Smart Inbox Assistant - initial schema
-- Deviation note: spec suggests Oracle/PL-SQL; Postgres is used here for local-dev
-- practicality (see write-up). Same relational modeling applies 1:1 if ported later.

CREATE TABLE messages (
    id              BIGSERIAL PRIMARY KEY,
    source_type     VARCHAR(20)  NOT NULL,             -- EMAIL | UPLOAD
    external_ref    VARCHAR(255),                       -- e.g. IMAP Message-Id, unique per mailbox
    sender          VARCHAR(255),
    subject         VARCHAR(1000),
    received_at     TIMESTAMP,
    body_text       TEXT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED', -- RECEIVED|QUEUED|PROCESSING|PROCESSED|FAILED
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_messages_external_ref ON messages(external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX ix_messages_status ON messages(status);

CREATE TABLE attachments (
    id              BIGSERIAL PRIMARY KEY,
    message_id      BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    filename        VARCHAR(500) NOT NULL,
    mime_type       VARCHAR(200),
    is_processable  BOOLEAN NOT NULL DEFAULT false,       -- false for non-PDF: logged only
    pdf_flavor      VARCHAR(30),                          -- DIGITAL|SCANNED_HANDWRITTEN|ARTICLE|NON_ENGLISH
    detected_language VARCHAR(20),
    storage_ref     VARCHAR(1000) NOT NULL,
    page_count      INT,
    processed       BOOLEAN NOT NULL DEFAULT false,
    summary         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_attachments_message_id ON attachments(message_id);

CREATE TABLE classifications (
    id                  BIGSERIAL PRIMARY KEY,
    message_id          BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    category            VARCHAR(20) NOT NULL,             -- ICSR|PQC|MI|NOT_RELEVANT
    confidence          NUMERIC(4,3) NOT NULL,
    reason              TEXT,
    model               VARCHAR(100),
    reviewer_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING|ACCEPTED|OVERRIDDEN|REJECTED
    reviewer_category   VARCHAR(20),
    reviewed_by         VARCHAR(255),
    reviewed_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_classifications_message_id ON classifications(message_id);

CREATE TABLE extracted_fields (
    id              BIGSERIAL PRIMARY KEY,
    message_id      BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    attachment_id   BIGINT REFERENCES attachments(id) ON DELETE CASCADE,
    field_group     VARCHAR(30) NOT NULL,   -- PATIENT|REPORTER|PRODUCT|REACTION|SEVERITY|NARRATIVE|PQC|MI
    field_name      VARCHAR(100) NOT NULL,
    field_value     TEXT NOT NULL,           -- literal value, or 'Not stated'
    confidence      NUMERIC(4,3),
    source_type     VARCHAR(20) NOT NULL,    -- EMAIL_BODY|PDF_PAGE
    source_ref      VARCHAR(255),            -- e.g. "page 3" / char offset range
    reviewer_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewer_value  TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_extracted_fields_message_id ON extracted_fields(message_id);
CREATE INDEX ix_extracted_fields_attachment_id ON extracted_fields(attachment_id);

CREATE TABLE tables_extracted (
    id              BIGSERIAL PRIMARY KEY,
    attachment_id   BIGINT NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    page_number     INT NOT NULL,
    table_json      JSONB NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_tables_extracted_attachment_id ON tables_extracted(attachment_id);

CREATE TABLE images_extracted (
    id                  BIGSERIAL PRIMARY KEY,
    attachment_id       BIGINT NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    page_number         INT NOT NULL,
    description         TEXT NOT NULL,
    flagged_for_review  BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_images_extracted_attachment_id ON images_extracted(attachment_id);

CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    entity_type     VARCHAR(50) NOT NULL,   -- MESSAGE|ATTACHMENT|CLASSIFICATION|EXTRACTED_FIELD|PROCESSING_JOB
    entity_id       BIGINT NOT NULL,
    actor           VARCHAR(255) NOT NULL,  -- 'system' or reviewer identity
    action          VARCHAR(100) NOT NULL,
    detail_json     JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_entity ON audit_log(entity_type, entity_id);
CREATE INDEX ix_audit_log_created_at ON audit_log(created_at);

CREATE TABLE processing_jobs (
    id              BIGSERIAL PRIMARY KEY,
    message_id      BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING|PROCESSING|DONE|FAILED
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    duration_ms     BIGINT,
    error           TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_processing_jobs_status ON processing_jobs(status);
CREATE INDEX ix_processing_jobs_message_id ON processing_jobs(message_id);
