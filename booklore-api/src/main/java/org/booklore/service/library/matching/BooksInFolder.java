package org.booklore.service.library.matching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The books that already have book files in one folder, with each of those files read as a title once, so that
 * matching every new file of the folder against them costs a few lookups rather than a pass over the folder.
 * <p>
 * A book's files are read with the folder's names and the book's own authors as evidence: "American Gods - Neil
 * Gaiman.epub" of a book by Neil Gaiman reads as "American Gods" even at the library's root.
 */
public final class BooksInFolder {

    public static final BooksInFolder EMPTY = new BooksInFolder();

    /**
     * A book with files in the folder.
     *
     * @param files       its book-format files in this folder, not those it has elsewhere
     * @param authorNames its authors as the metadata has them; empty when unknown
     */
    public record FolderBook(long bookId, List<FolderFile> files, List<String> authorNames) {
        public FolderBook {
            files = List.copyOf(files);
            authorNames = authorNames == null ? List.of() : authorNames.stream().filter(Objects::nonNull).toList();
        }
    }

    /** One book-format file: its name, and whether it is an audiobook (a folder audiobook's name is its folder's). */
    public record FolderFile(String fileName, boolean audio) {
    }

    /** One book's file read as a title. */
    record Entry(long bookId, TitleKey key) {
    }

    private final Map<String, List<Entry>> byIdentity = new HashMap<>();
    /** Files with a copy marker (" (1)", " - Copy"), by the identity of the file they duplicate. */
    private final Map<String, List<Entry>> copiesByBaseIdentity = new HashMap<>();
    private final Map<String, Set<Long>> byBaseName = new HashMap<>();
    private final Map<Long, List<PersonName>> authorsByBook = new HashMap<>();

    public static BooksInFolder of(String folder, Collection<FolderBook> books) {
        return of(NameContext.forFolder(folder), books);
    }

    public static BooksInFolder of(NameContext folderContext, Collection<FolderBook> books) {
        BooksInFolder index = new BooksInFolder();
        for (FolderBook book : books) {
            NameContext context = folderContext.withAuthors(book.authorNames());
            index.authorsByBook.put(book.bookId(),
                    book.authorNames().stream().map(PersonName::parse).filter(Objects::nonNull).toList());
            for (FolderFile file : book.files()) {
                TitleKey key = TitleKey.ofFileName(file.fileName(), file.audio(), context);
                Entry entry = new Entry(book.bookId(), key);
                // A name with no word or number left ("(Digital).cbz") is found only by its base name
                if (!key.identity().isEmpty()) {
                    index.byIdentity.computeIfAbsent(key.identity(), k -> new ArrayList<>(1)).add(entry);
                }
                if (key.copy() != null && !key.identityWithoutCopy().isEmpty()) {
                    index.copiesByBaseIdentity.computeIfAbsent(key.identityWithoutCopy(), k -> new ArrayList<>(1)).add(entry);
                }
                index.byBaseName.computeIfAbsent(FileEvidence.baseNameKey(file.fileName()), k -> new LinkedHashSet<>())
                        .add(book.bookId());
            }
        }
        return index;
    }

    public boolean isEmpty() {
        return authorsByBook.isEmpty();
    }

    /** The books with a file whose name reads as this identity. */
    List<Entry> withIdentity(String identity) {
        return byIdentity.getOrDefault(identity, List.of());
    }

    /** The books with a duplicate's copy of a file whose name reads as this identity. */
    List<Entry> copiesOf(String identity) {
        return copiesByBaseIdentity.getOrDefault(identity, List.of());
    }

    /** The books with a file of this base name, compared without case. */
    Set<Long> withBaseName(String baseNameKey) {
        return byBaseName.getOrDefault(baseNameKey, Set.of());
    }

    List<PersonName> authorsOf(long bookId) {
        return authorsByBook.getOrDefault(bookId, List.of());
    }
}
