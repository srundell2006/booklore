-- task_options is TEXT (64 KB), which overflows when serialising book-ID lists
-- for large library refreshes (e.g. 11 000+ books).  MEDIUMTEXT holds up to 16 MB.
ALTER TABLE tasks MODIFY COLUMN task_options MEDIUMTEXT;
