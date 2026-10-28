-- Library and all-books counts, and the visible-book scans behind the catalog's other counts and the random row
CREATE INDEX IF NOT EXISTS idx_book_library_deleted ON book (library_id, deleted);

-- The recently-added row: ORDER BY added_on DESC, id ASC LIMIT n reads this index forward
CREATE INDEX IF NOT EXISTS idx_book_added_on_id ON book (added_on DESC, id);
