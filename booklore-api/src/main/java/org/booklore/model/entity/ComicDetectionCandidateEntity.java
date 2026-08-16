package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.ComicCandidateStatus;
import org.booklore.model.enums.ComicVerdict;

import java.time.Instant;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "comic_detection_candidate")
public class ComicDetectionCandidateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_id", nullable = false, unique = true)
    private Long bookId;

    @Column(name = "score", nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 20)
    private ComicVerdict verdict;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ComicCandidateStatus status = ComicCandidateStatus.PENDING;

    /** Human-readable reasons, one per line, e.g. "EPUB is fixed-layout (+30)". */
    @Column(name = "signals", columnDefinition = "TEXT")
    private String signals;

    @Column(name = "detected_at", nullable = false)
    @Builder.Default
    private Instant detectedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;
}
