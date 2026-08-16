package org.booklore.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** A borderline comic-detection result presented for review. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComicCandidateDto {

    private Long id;
    private Long bookId;
    private String title;
    private String authors;
    private String publisher;
    private String fileName;
    private String fileType;
    private int score;
    private String verdict;
    private String status;
    /** One reason per entry, e.g. "EPUB declares fixed-layout (+30)". */
    private List<String> signals;
    private Instant detectedAt;
}
