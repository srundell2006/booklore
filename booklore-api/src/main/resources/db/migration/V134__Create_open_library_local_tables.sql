-- Open Library local import tables
-- Populated by import_open_library.py using bulk data dumps from openlibrary.org

CREATE TABLE IF NOT EXISTS ol_authors (
    ol_key      VARCHAR(50)   NOT NULL,
    author_name VARCHAR(1000) NOT NULL,
    PRIMARY KEY (ol_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ol_editions (
    ol_key       VARCHAR(50)   NOT NULL,
    isbn13       VARCHAR(13)   DEFAULT NULL,
    isbn10       VARCHAR(10)   DEFAULT NULL,
    title        VARCHAR(1000) DEFAULT NULL,
    subtitle     VARCHAR(1000) DEFAULT NULL,
    publisher    VARCHAR(500)  DEFAULT NULL,
    publish_date VARCHAR(100)  DEFAULT NULL,
    description  TEXT          DEFAULT NULL,
    subjects     TEXT          DEFAULT NULL,
    author_keys  TEXT          DEFAULT NULL,
    language     VARCHAR(10)   DEFAULT NULL,
    page_count   INT           DEFAULT NULL,
    cover_id     BIGINT        DEFAULT NULL,
    PRIMARY KEY (ol_key),
    INDEX idx_ol_isbn13 (isbn13),
    INDEX idx_ol_isbn10 (isbn10),
    FULLTEXT INDEX ft_ol_title (title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
