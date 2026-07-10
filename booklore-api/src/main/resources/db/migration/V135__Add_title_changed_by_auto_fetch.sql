ALTER TABLE book_metadata
    ADD COLUMN IF NOT EXISTS title_changed_by_auto_fetch BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS previous_title              VARCHAR(1000) DEFAULT NULL;
