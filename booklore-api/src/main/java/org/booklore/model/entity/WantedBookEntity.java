package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.WantedBookStatus;

import java.time.Instant;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "wanted_book")
public class WantedBookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "author", length = 512)
    private String author;

    @Column(name = "isbn13", length = 20)
    private String isbn13;

    @Column(name = "asin", length = 20)
    private String asin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private WantedBookStatus status = WantedBookStatus.WANTED;

    @Column(name = "preferred_format", length = 20)
    private String preferredFormat;

    @Column(name = "added_at", nullable = false)
    @Builder.Default
    private Instant addedAt = Instant.now();

    @Column(name = "last_search_at")
    private Instant lastSearchAt;

    @Column(name = "grabbed_at")
    private Instant grabbedAt;

    @Column(name = "grabbed_release_title", length = 1024)
    private String grabbedReleaseTitle;

    @Column(name = "grabbed_indexer")
    private String grabbedIndexer;

    @Column(name = "download_client", length = 20)
    private String downloadClient;

    @Column(name = "download_id")
    private String downloadId;

    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    @Column(name = "search_attempts", nullable = false)
    @Builder.Default
    private int searchAttempts = 0;

    @Column(name = "imported_book_id")
    private Long importedBookId;

    @Column(name = "added_by_user_id")
    private Long addedByUserId;
}
