package org.booklore.repository;

import org.booklore.model.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface BookMetadataRepository extends JpaRepository<BookMetadataEntity, Long> {

    @Query("SELECT m FROM BookMetadataEntity m WHERE m.bookId IN :bookIds")
    List<BookMetadataEntity> getMetadataForBookIds(@Param("bookIds") List<Long> bookIds);

    @Modifying
    @Transactional
    @Query("UPDATE BookMetadataEntity m SET m.coverUpdatedOn = :timestamp WHERE m.bookId = :bookId")
    void updateCoverTimestamp(@Param("bookId") Long bookId, @Param("timestamp") Instant timestamp);

    @Modifying
    @Transactional
    @Query("UPDATE BookMetadataEntity m SET m.audiobookCoverUpdatedOn = :timestamp WHERE m.bookId = :bookId")
    void updateAudiobookCoverTimestamp(@Param("bookId") Long bookId, @Param("timestamp") Instant timestamp);

    List<BookMetadataEntity> findAllByAuthorsContaining(AuthorEntity author);

    List<BookMetadataEntity> findAllByCategoriesContaining(CategoryEntity category);

    List<BookMetadataEntity> findAllByMoodsContaining(MoodEntity mood);

    List<BookMetadataEntity> findAllByTagsContaining(TagEntity tag);

    List<BookMetadataEntity> findAllBySeriesNameIgnoreCase(String seriesName);

    List<BookMetadataEntity> findAllByPublisherIgnoreCase(String publisher);

    List<BookMetadataEntity> findAllByLanguageIgnoreCase(String language);

    @Modifying
    @Transactional
    @Query("UPDATE BookMetadataEntity m SET m.titleChangedByAutoFetch = false, m.previousTitle = null WHERE m.bookId IN :bookIds")
    void clearTitleChangedFlag(@Param("bookIds") List<Long> bookIds);

    /**
     * Returns IDs of books that are audiobooks (have at least one AUDIOBOOK-type book file)
     * and have never been verified (verification_status IS NULL).
     */
    @Query("SELECT DISTINCT m.bookId FROM BookMetadataEntity m " +
           "WHERE m.verificationStatus IS NULL " +
           "AND m.bookId IN (" +
           "  SELECT DISTINCT f.book.id FROM BookFileEntity f " +
           "  WHERE f.bookType = org.booklore.model.enums.BookFileType.AUDIOBOOK" +
           ")")
    List<Long> findUnverifiedAudiobookIds();

    /**
     * Writes the result of a single audiobook verification check.
     */
    @Modifying
    @Transactional
    @Query("UPDATE BookMetadataEntity m SET " +
           "m.verificationStatus = :status, " +
           "m.verificationDetectedTitle = :detectedTitle, " +
           "m.verificationDetectedAuthors = :detectedAuthors, " +
           "m.verificationCheckedAt = :checkedAt, " +
           "m.verificationMismatchReason = :mismatchReason " +
           "WHERE m.bookId = :bookId")
    void updateVerificationResult(
            @Param("bookId") Long bookId,
            @Param("status") String status,
            @Param("detectedTitle") String detectedTitle,
            @Param("detectedAuthors") String detectedAuthors,
            @Param("checkedAt") Instant checkedAt,
            @Param("mismatchReason") String mismatchReason);
}
