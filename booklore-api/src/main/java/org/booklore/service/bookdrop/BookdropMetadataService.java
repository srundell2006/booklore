package org.booklore.service.bookdrop;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.MetadataRefreshService;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.booklore.model.entity.BookdropFileEntity.Status.PENDING_REVIEW;

@Slf4j
@AllArgsConstructor
@Service
public class BookdropMetadataService {

    private final BookdropFileRepository bookdropFileRepository;
    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;
    private final MetadataExtractorFactory metadataExtractorFactory;
    private final MetadataRefreshService metadataRefreshService;
    private final FileService fileService;

    @Transactional
    public BookdropFileEntity attachInitialMetadata(Long bookdropFileId) throws JacksonException {
        BookdropFileEntity entity = getOrThrow(bookdropFileId);
        BookMetadata initial = extractInitialMetadata(entity);
        if (initial == null) {
            log.warn("Metadata extraction returned null for file: {}. Using filename as fallback.", entity.getFileName());
            initial = BookMetadata.builder()
                    .title(FilenameUtils.getBaseName(entity.getFileName()))
                    .build();
        }
        extractAndSaveCover(entity);
        String initialJson = objectMapper.writeValueAsString(initial);
        entity.setOriginalMetadata(initialJson);
        entity.setUpdatedAt(Instant.now());
        return bookdropFileRepository.save(entity);
    }

    @Transactional
    public BookdropFileEntity attachFetchedMetadata(Long bookdropFileId) throws JacksonException {
        BookdropFileEntity entity = getOrThrow(bookdropFileId);

        AppSettings appSettings = appSettingService.getAppSettings();

        MetadataRefreshOptions refreshOptions = appSettings.getDefaultMetadataRefreshOptions();

        BookMetadata initial = objectMapper.readValue(entity.getOriginalMetadata(), BookMetadata.class);

        List<MetadataProvider> providers = metadataRefreshService.prepareProviders(refreshOptions);
        Book book = Book.builder()
                .primaryFile(BookFile.builder().fileName(entity.getFileName()).build())
                .metadata(initial)
                .build();

        if (providers.contains(MetadataProvider.GoodReads)) {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(250, 1250));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        Map<MetadataProvider, BookMetadata> metadataMap = metadataRefreshService.fetchMetadataForBook(providers, book);
        BookMetadata fetchedMetadata = metadataRefreshService.buildFetchMetadata(initial, book.getId(), refreshOptions, metadataMap);
        String fetchedJson = objectMapper.writeValueAsString(fetchedMetadata);

        entity.setFetchedMetadata(fetchedJson);
        entity.setMatchScore(computeMatchScore(initial, fetchedMetadata));
        entity.setStatus(PENDING_REVIEW);
        entity.setUpdatedAt(Instant.now());

        return bookdropFileRepository.save(entity);
    }

    private BookdropFileEntity getOrThrow(Long id) {
        return bookdropFileRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Bookdrop file not found: " + id));
    }

    private BookMetadata extractInitialMetadata(BookdropFileEntity entity) {
        File file = new File(entity.getFilePath());
        BookFileExtension fileExt = BookFileExtension.fromFileName(file.getName())
            .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported file extension"));
        return metadataExtractorFactory.extractMetadata(fileExt, file);
    }

    private void extractAndSaveCover(BookdropFileEntity entity) {
        File file = new File(entity.getFilePath());
        BookFileExtension fileExt = BookFileExtension.fromFileName(file.getName())
            .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported file extension"));
        byte[] coverBytes = metadataExtractorFactory.extractCover(fileExt, file);
        if (coverBytes != null) {
            try {
                FileService.saveImage(coverBytes, fileService.getTempBookdropCoverImagePath(entity.getId()));
            } catch (IOException e) {
                log.warn("Failed to save extracted cover for file: {}", entity.getFilePath(), e);
            }
        }
    }

    /**
     * Computes a 0–100 match score comparing fetched metadata against the original.
     * <ul>
     *   <li>Title similarity (Levenshtein normalised): 0–70 points</li>
     *   <li>Author overlap (Jaccard): 0–20 points</li>
     *   <li>ISBN match bonus: 0–10 points</li>
     * </ul>
     */
    private static int computeMatchScore(BookMetadata original, BookMetadata fetched) {
        if (fetched == null || fetched.getTitle() == null) return 0;

        // Title similarity: 0-70 points
        String origTitle = normalizeTitle(original != null ? original.getTitle() : null);
        String fetchTitle = normalizeTitle(fetched.getTitle());
        int titleScore = 0;
        if (!fetchTitle.isEmpty()) {
            if (origTitle.isEmpty()) {
                titleScore = 35; // no original title to compare against
            } else {
                int maxLen = Math.max(origTitle.length(), fetchTitle.length());
                int dist = levenshteinDistance(origTitle, fetchTitle);
                titleScore = (int) Math.round(70.0 * (1.0 - (double) dist / maxLen));
            }
        }

        // Author overlap: 0-20 points (Jaccard similarity)
        int authorScore = 0;
        List<String> origAuthors = normalizeAuthors(original != null ? original.getAuthors() : null);
        List<String> fetchAuthors = normalizeAuthors(fetched.getAuthors());
        if (!fetchAuthors.isEmpty()) {
            if (origAuthors.isEmpty()) {
                authorScore = 10; // can't compare, partial credit
            } else {
                long matched = fetchAuthors.stream().filter(origAuthors::contains).count();
                Set<String> union = new HashSet<>(origAuthors);
                union.addAll(fetchAuthors);
                authorScore = union.isEmpty() ? 0 : (int) Math.round(20.0 * matched / union.size());
            }
        }

        // ISBN match: 0-10 points
        int isbnScore = 0;
        boolean fetchedHasIsbn = fetched.getIsbn13() != null || fetched.getIsbn10() != null;
        boolean originalHasIsbn = original != null && (original.getIsbn13() != null || original.getIsbn10() != null);
        if (fetchedHasIsbn) {
            if (!originalHasIsbn) {
                isbnScore = 5; // fetched has ISBN but original doesn't — still useful
            } else if ((fetched.getIsbn13() != null && fetched.getIsbn13().equals(original.getIsbn13()))
                    || (fetched.getIsbn10() != null && fetched.getIsbn10().equals(original.getIsbn10()))) {
                isbnScore = 10;
            }
        }

        return Math.min(100, Math.max(0, titleScore + authorScore + isbnScore));
    }

    private static String normalizeTitle(String s) {
        if (s == null || s.isBlank()) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
    }

    private static List<String> normalizeAuthors(List<String> authors) {
        if (authors == null) return List.of();
        return authors.stream()
                .filter(a -> a != null && !a.isBlank())
                .map(a -> a.toLowerCase().trim())
                .collect(Collectors.toList());
    }

    private static int levenshteinDistance(String a, String b) {
        int la = a.length(), lb = b.length();
        int[] dp = new int[lb + 1];
        for (int j = 0; j <= lb; j++) dp[j] = j;
        for (int i = 1; i <= la; i++) {
            int prev = dp[0];
            dp[0] = i;
            for (int j = 1; j <= lb; j++) {
                int temp = dp[j];
                dp[j] = (a.charAt(i - 1) == b.charAt(j - 1))
                        ? prev
                        : 1 + Math.min(prev, Math.min(dp[j], dp[j - 1]));
                prev = temp;
            }
        }
        return dp[lb];
    }
}
