ALTER TABLE book_metadata
    ADD COLUMN IF NOT EXISTS verification_status           VARCHAR(20)    NULL,
    ADD COLUMN IF NOT EXISTS verification_detected_title   VARCHAR(500)   NULL,
    ADD COLUMN IF NOT EXISTS verification_detected_authors VARCHAR(1000)  NULL,
    ADD COLUMN IF NOT EXISTS verification_checked_at       DATETIME(6)    NULL,
    ADD COLUMN IF NOT EXISTS verification_mismatch_reason  TEXT           NULL;
