package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.repository.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Flattens a chunk of books into plain values for the parallel file check.
 *
 * <p>Separate bean on purpose: {@code @Transactional} is proxy-applied, so a
 * same-class call would bypass it and the LAZY tag collection would blow up
 * outside a session. Resolving paths and tag state here also means the checking
 * threads touch no entities and hold no database connection while they wait on
 * slow storage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissingFileScanLoader {

    private final BookRepository bookRepository;

    /** One book's identity, file paths and current tag state. */
    public record FileCheckTarget(Long bookId, String title, List<FileRef> files, boolean alreadyTagged) {
    }

    public record FileRef(String name, Path path) {
    }

    @Transactional(readOnly = true)
    public List<FileCheckTarget> load(Set<Long> bookIds, String tagName) {
        List<FileCheckTarget> targets = new ArrayList<>();

        for (BookEntity book : bookRepository.findAllWithMetadataByIds(bookIds)) {
            BookMetadataEntity meta = book.getMetadata();
            if (meta == null) continue;

            List<FileRef> files = new ArrayList<>();
            if (book.getBookFiles() != null) {
                for (BookFileEntity file : book.getBookFiles()) {
                    String name = file.getFileName() != null ? file.getFileName() : "(unnamed)";
                    Path path;
                    try {
                        path = file.getFullFilePath();
                    } catch (Exception e) {
                        path = null;
                    }
                    files.add(new FileRef(name, path));
                }
            }

            targets.add(new FileCheckTarget(book.getId(), title(book, meta), files, hasTag(meta, tagName)));
        }
        return targets;
    }

    private boolean hasTag(BookMetadataEntity meta, String tagName) {
        Set<TagEntity> tags = meta.getTags();
        return tags != null && tags.stream()
                .anyMatch(t -> t.getName() != null && t.getName().equalsIgnoreCase(tagName));
    }

    private String title(BookEntity book, BookMetadataEntity meta) {
        if (meta.getTitle() != null && !meta.getTitle().isBlank()) return meta.getTitle();
        BookFileEntity primary = book.getPrimaryBookFile();
        return primary != null && primary.getFileName() != null
                ? primary.getFileName()
                : "Book " + book.getId();
    }
}
