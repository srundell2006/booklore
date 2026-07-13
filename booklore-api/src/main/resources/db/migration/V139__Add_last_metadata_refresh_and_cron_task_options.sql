ALTER TABLE book ADD COLUMN last_metadata_refresh_at DATETIME NULL;

ALTER TABLE task_cron_configuration ADD COLUMN task_options TEXT NULL;
