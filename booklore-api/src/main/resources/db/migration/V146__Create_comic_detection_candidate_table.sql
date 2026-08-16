CREATE TABLE IF NOT EXISTS comic_detection_candidate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    book_id BIGINT NOT NULL,
    score INT NOT NULL,
    verdict VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    signals TEXT,
    detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP NULL,
    resolved_by_user_id BIGINT NULL,
    UNIQUE KEY uq_comic_candidate_book (book_id),
    KEY idx_comic_candidate_status (status),
    CONSTRAINT fk_comic_candidate_book FOREIGN KEY (book_id)
        REFERENCES book (id) ON DELETE CASCADE
);
