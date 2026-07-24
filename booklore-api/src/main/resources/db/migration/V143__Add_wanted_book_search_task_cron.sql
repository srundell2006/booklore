INSERT INTO task_cron_configuration (task_type, cron_expression, enabled, created_by)
SELECT 'WANTED_BOOK_SEARCH', '0 0 */6 * * *', FALSE, -1
WHERE NOT EXISTS (
    SELECT 1 FROM task_cron_configuration WHERE task_type = 'WANTED_BOOK_SEARCH'
);
