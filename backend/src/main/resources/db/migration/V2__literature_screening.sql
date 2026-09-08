-- Bonus: Literature Screening (Section 4). Articles are uploaded independently
-- (not via the mailbox) as messages with source_type='UPLOAD', reusing the
-- existing ingestion/queue/audit pipeline. An article can describe zero, one,
-- or multiple real patient cases; each becomes its own literature_cases row.

CREATE TABLE literature_cases (
    id                  BIGSERIAL PRIMARY KEY,
    message_id          BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    attachment_id       BIGINT NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    case_number         INT NOT NULL,               -- 1-based, per attachment
    is_relevant_case    BOOLEAN NOT NULL,            -- describes a real, identifiable patient case worth reporting
    relevance_reason    TEXT NOT NULL,
    confidence          NUMERIC(4,3) NOT NULL,
    summary             TEXT NOT NULL,
    reviewer_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewer_is_relevant BOOLEAN,
    reviewed_by         VARCHAR(255),
    reviewed_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_literature_cases_message_id ON literature_cases(message_id);
CREATE UNIQUE INDEX ux_literature_cases_attachment_case ON literature_cases(attachment_id, case_number);

-- extracted_fields rows for a literature case (PATIENT/PRODUCT/REACTION/etc.,
-- same field_group taxonomy as ICSR extraction) are tagged with which case
-- they belong to; NULL for ordinary email-derived fields.
ALTER TABLE extracted_fields ADD COLUMN case_number INT;
