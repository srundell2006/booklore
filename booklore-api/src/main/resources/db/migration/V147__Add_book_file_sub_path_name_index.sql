-- A scan looks a file up by its folder and name: the books already in a folder when it groups new files, and whether
-- a path is already stored when it creates a book or attaches a file. Without an index each lookup read all of
-- book_file, tens of milliseconds per new file in a library of 100k books.
--
-- Only the first 255 characters of the name are indexed: the whole name (1000 characters) next to the sub path (512)
-- is over InnoDB's 3072-byte key limit in utf8mb4, and 3068 bytes fit only because book_file has the DYNAMIC row format
-- (MariaDB's default since 10.2; COMPACT allows 767). Rows sharing a sub path and a 255-character prefix are told apart
-- by reading them. No ALGORITHM or LOCK clause: MariaDB adds an index in place without blocking writes by itself, and
-- an explicit clause would turn its fallback into a failed migration.
CREATE INDEX IF NOT EXISTS idx_book_file_sub_path_name ON book_file (file_sub_path, file_name(255));
