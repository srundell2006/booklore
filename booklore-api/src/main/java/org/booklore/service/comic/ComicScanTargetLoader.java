package org.booklore.service.comic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.repository.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads {@link ComicScanTarget}s a chunk at a time.
 *
 * <p>Deliberately a separate bean: {@code @Transactional} is applied by a proxy,
 * so calling a transactional method from inside the same class would bypass it
 * entirely and reintroduce the lazy-loading failure this exists to prevent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComicScanTargetLoader {

    private final BookRepository bookRepository;

    @Transactional(readOnly = true)
    public List<ComicScanTarget> load(Set<Long> bookIds) {
        return bookRepository.findAllWithMetadataByIds(bookIds).stream()
                .map(this::toTarget)
                .toList();
    }

    private ComicScanTarget toTarget(BookEntity book) {
        BookMetadataEntity metadata = book.getMetadata();
        BookFileEntity primary = book.getPrimaryBookFile();

        Set<String> categories = new LinkedHashSet<>();
        if (metadata != null) {
            try {
                Set<CategoryEntity> loaded = metadata.getCategories();
                if (loaded != null) {
                    for (CategoryEntity category : loaded) {
                        if (category.getName() != null && !category.getName().isBlank()) {
                            categories.add(category.getName());
                        }
                    }
                }
            } catch (Exception e) {
                // A book with unreadable categories should still be scannable.
                log.debug("ComicScanTargetLoader: categories unavailable for book {}: {}",
                        book.getId(), e.getMessage());
            }
        }

        String filePath = null;
        try {
            if (primary != null && primary.getFullFilePath() != null) {
                filePath = primary.getFullFilePath().toString();
            }
        } catch (Exception e) {
            log.debug("ComicScanTargetLoader: path unavailable for book {}: {}",
                    book.getId(), e.getMessage());
        }

        return new ComicScanTarget(
                book.getId(),
                metadata != null ? metadata.getTitle() : null,
                metadata != null ? metadata.getSeriesName() : null,
                metadata != null ? metadata.getPublisher() : null,
                categories,
                filePath,
                primary != null ? primary.getFileName() : null,
                primary != null ? primary.getBookType() : null,
                Boolean.TRUE.equals(book.getIsComic())
        );
    }
}
