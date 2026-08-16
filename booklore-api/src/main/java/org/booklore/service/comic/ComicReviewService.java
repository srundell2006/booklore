package org.booklore.service.comic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.ComicCandidateDto;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.ComicDetectionCandidateEntity;
import org.booklore.model.enums.ComicCandidateStatus;
import org.booklore.repository.BookRepository;
import org.booklore.repository.ComicDetectionCandidateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Read and resolve the borderline queue produced by {@link ComicDetectionService}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComicReviewService {

    private final ComicDetectionCandidateRepository candidateRepository;
    private final BookRepository bookRepository;

    @Transactional(readOnly = true)
    public List<ComicCandidateDto> listPending() {
        List<ComicDetectionCandidateEntity> candidates =
                candidateRepository.findByStatusOrderByScoreDesc(ComicCandidateStatus.PENDING);
        if (candidates.isEmpty()) return List.of();

        Set<Long> bookIds = candidates.stream()
                .map(ComicDetectionCandidateEntity::getBookId)
                .collect(Collectors.toSet());

        Map<Long, BookEntity> books = bookRepository.findAllWithMetadataByIds(bookIds).stream()
                .collect(Collectors.toMap(BookEntity::getId, b -> b, (a, b) -> a));

        return candidates.stream()
                .map(candidate -> toDto(candidate, books.get(candidate.getBookId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return candidateRepository.countByStatus(ComicCandidateStatus.PENDING);
    }

    /** Confirm a candidate: sets {@code is_comic} on the book. */
    @Transactional
    public void accept(Long candidateId, Long userId) {
        resolve(candidateId, userId, ComicCandidateStatus.ACCEPTED, true);
    }

    /** Dismiss a candidate: leaves the book untouched so it is not re-queued. */
    @Transactional
    public void reject(Long candidateId, Long userId) {
        resolve(candidateId, userId, ComicCandidateStatus.REJECTED, false);
    }

    @Transactional
    public int acceptAll(Collection<Long> candidateIds, Long userId) {
        int count = 0;
        for (Long id : candidateIds) {
            try {
                resolve(id, userId, ComicCandidateStatus.ACCEPTED, true);
                count++;
            } catch (Exception e) {
                log.warn("ComicReview: could not accept candidate {}: {}", id, e.getMessage());
            }
        }
        return count;
    }

    @Transactional
    public int rejectAll(Collection<Long> candidateIds, Long userId) {
        int count = 0;
        for (Long id : candidateIds) {
            try {
                resolve(id, userId, ComicCandidateStatus.REJECTED, false);
                count++;
            } catch (Exception e) {
                log.warn("ComicReview: could not reject candidate {}: {}", id, e.getMessage());
            }
        }
        return count;
    }

    /** Clears resolved rows so a future scan starts from a clean slate. */
    @Transactional
    public long clearResolved() {
        List<ComicDetectionCandidateEntity> resolved = new ArrayList<>();
        resolved.addAll(candidateRepository.findByStatusOrderByScoreDesc(ComicCandidateStatus.ACCEPTED));
        resolved.addAll(candidateRepository.findByStatusOrderByScoreDesc(ComicCandidateStatus.REJECTED));
        candidateRepository.deleteAll(resolved);
        return resolved.size();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void resolve(Long candidateId, Long userId, ComicCandidateStatus status, boolean markComic) {
        ComicDetectionCandidateEntity candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));

        if (markComic) {
            bookRepository.findById(candidate.getBookId()).ifPresent(book -> {
                book.setIsComic(Boolean.TRUE);
                bookRepository.save(book);
            });
        }

        candidate.setStatus(status);
        candidate.setResolvedAt(Instant.now());
        candidate.setResolvedByUserId(userId);
        candidateRepository.save(candidate);

        log.info("ComicReview: candidate {} (book {}) resolved as {}",
                candidateId, candidate.getBookId(), status);
    }

    private ComicCandidateDto toDto(ComicDetectionCandidateEntity candidate, BookEntity book) {
        BookMetadataEntity metadata = book != null ? book.getMetadata() : null;
        BookFileEntity primary = book != null ? book.getPrimaryBookFile() : null;

        String authors = "";
        if (metadata != null && metadata.getAuthors() != null) {
            authors = metadata.getAuthors().stream()
                    .map(a -> a.getName() == null ? "" : a.getName())
                    .filter(n -> !n.isBlank())
                    .collect(Collectors.joining(", "));
        }

        List<String> signals = candidate.getSignals() == null || candidate.getSignals().isBlank()
                ? List.of()
                : Arrays.asList(candidate.getSignals().split("\n"));

        return ComicCandidateDto.builder()
                .id(candidate.getId())
                .bookId(candidate.getBookId())
                .title(metadata != null && metadata.getTitle() != null ? metadata.getTitle() : "(unknown)")
                .authors(authors)
                .publisher(metadata != null ? metadata.getPublisher() : null)
                .fileName(primary != null ? primary.getFileName() : null)
                .fileType(primary != null && primary.getBookType() != null
                        ? primary.getBookType().name() : null)
                .score(candidate.getScore())
                .verdict(candidate.getVerdict() != null ? candidate.getVerdict().name() : null)
                .status(candidate.getStatus().name())
                .signals(signals)
                .detectedAt(candidate.getDetectedAt())
                .build();
    }
}
