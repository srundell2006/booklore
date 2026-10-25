package org.booklore.service.library;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.FilelessBookRow;
import org.booklore.service.library.matching.FilelessShellIndex;
import org.booklore.service.library.matching.ShellCandidate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A library's fileless books, read with one projection query into a {@link FilelessShellIndex}. A rescan reads them
 * afresh; the watcher, which handles one file at a time, keeps them for a while instead of reading every fileless
 * book of the library for every new file. A book that gets a file is taken out of the kept index at once; one that
 * becomes fileless meanwhile is only seen when the index is read again, and until then its file becomes a new book,
 * which is the safe way to miss. A book edited meanwhile (its title, authors, series or physical flag) is still found
 * as it was: {@link ExistingBookAttacher} matches the file against the book as it is now before attaching it, and has
 * the index read again when they no longer match.
 */
@Component
@RequiredArgsConstructor
public class FilelessShells {

    static final Duration WATCHER_INDEX_LIFETIME = Duration.ofMinutes(10);

    private final BookRepository bookRepository;

    private final Cache<Long, FilelessShellIndex> watcherIndexes = Caffeine.newBuilder()
            .expireAfterWrite(WATCHER_INDEX_LIFETIME)
            .build();

    /** The library's fileless books as they are now. */
    public FilelessShellIndex load(long libraryId) {
        return FilelessShellIndex.of(candidates(bookRepository.findFilelessBookRowsByLibraryId(libraryId)));
    }

    /** The library's fileless books as the watcher last read them, read again when that was too long ago. */
    public FilelessShellIndex forWatcher(long libraryId) {
        return watcherIndexes.get(libraryId, this::load);
    }

    /** Has the watcher read the library's fileless books again on its next event: one was edited since they were read. */
    public void readAgainForWatcher(long libraryId) {
        watcherIndexes.invalidate(libraryId);
    }

    /** Takes a book that got a file, or is gone, out of the watcher's index. */
    public void notFileless(long bookId) {
        watcherIndexes.asMap().values().forEach(index -> index.remove(bookId));
    }

    /** One candidate per book, from rows that come one per author, in book id order. */
    static List<ShellCandidate> candidates(List<FilelessBookRow> rows) {
        Map<Long, List<FilelessBookRow>> byBook = new LinkedHashMap<>();
        for (FilelessBookRow row : rows) {
            byBook.computeIfAbsent(row.bookId(), id -> new ArrayList<>()).add(row);
        }
        List<ShellCandidate> candidates = new ArrayList<>(byBook.size());
        byBook.forEach((bookId, bookRows) -> {
            FilelessBookRow first = bookRows.getFirst();
            List<String> authors = bookRows.stream().map(FilelessBookRow::authorName).filter(name -> name != null && !name.isBlank()).toList();
            boolean hasCover = first.bookCoverHash() != null || first.audiobookCoverHash() != null;
            candidates.add(ShellCandidate.of(bookId, first.libraryPathId(), Boolean.TRUE.equals(first.physical()), hasCover,
                    first.title(), first.subtitle(), first.seriesName(), first.seriesNumber(), authors));
        });
        return candidates;
    }
}
