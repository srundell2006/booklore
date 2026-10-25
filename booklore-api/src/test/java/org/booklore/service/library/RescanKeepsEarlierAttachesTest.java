package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookAuthorRow;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.repository.projection.FilelessBookRow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The choice among alike fileless books and a " (1)" read as a copy only ever add attaches: every file the rules of
 * before (develop at a47211e7, which had neither) attached to a book, a rescan still attaches to that same book. The
 * cases come from BookGroupingServiceTest, ExistingBookMatcherTest, RescanDryRunTest and the dry run of a real library,
 * where the new rules once took a book from 12 such files; each was checked against the rules of before. Files a case
 * does not list may get a book now that they got none before.
 */
class RescanKeepsEarlierAttachesTest {

    /**
     * A library, the new files of its rescan, and the book the rules of before gave each file they attached.
     *
     * @param existing book id, sub path, file name and the book's authors ("; "-joined), of books with files
     * @param newFiles "sub/path/name", the folder being everything before the last "/"
     * @param before   by "sub/path/name", the book each file the rules of before attached went to
     */
    record Case(String name, LibraryOrganizationMode mode, List<FilelessBookRow> shells, List<String[]> existing,
                List<String> newFiles, Map<String, Long> before) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * A fileless book with one row per author, as BookRepository.findFilelessBookRowsByLibraryId gives it; one without
     * authors is a physical book, the only kind that matches without them.
     */
    private static List<FilelessBookRow> shellOfSeries(long id, String title, String subtitle, String seriesName,
                                                       Float seriesNumber, String... authors) {
        List<FilelessBookRow> rows = new ArrayList<>();
        for (String author : authors.length == 0 ? new String[]{null} : authors) {
            rows.add(new FilelessBookRow(id, null, authors.length == 0, "cover", null, title, subtitle, seriesName,
                    seriesNumber, author));
        }
        return rows;
    }

    private static List<FilelessBookRow> shell(long id, String title, String... authors) {
        return shellOfSeries(id, title, null, null, null, authors);
    }

    @SafeVarargs
    private static List<FilelessBookRow> shells(List<FilelessBookRow>... books) {
        return Stream.of(books).flatMap(List::stream).toList();
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("a fileless book, and a book with files in the folder", LibraryOrganizationMode.AUTO_DETECT,
                        shell(300, "The Martian", "Andy Weir"),
                        List.<String[]>of(new String[]{"400", "Andy Weir/Hail Mary", "Project Hail Mary.epub", "Andy Weir"}),
                        List.of("Andy Weir/Martian/The Martian.epub", "Andy Weir/Martian/The Martian.m4b",
                                "Andy Weir/Hail Mary/Project Hail Mary.pdf", "Andy Weir/Hail Mary/Unrelated Thing.pdf"),
                        Map.of("Andy Weir/Martian/The Martian.epub", 300L, "Andy Weir/Martian/The Martian.m4b", 300L,
                                "Andy Weir/Hail Mary/Project Hail Mary.pdf", 400L)),
                new Case("a physical book without authors", LibraryOrganizationMode.AUTO_DETECT,
                        shells(shell(301, "Dune"), shell(302, "The Martian", "Andy Weir")), List.of(),
                        List.of("Herbert/Dune.epub"),
                        Map.of("Herbert/Dune.epub", 301L)),
                new Case("a copy goes where its original goes", LibraryOrganizationMode.AUTO_DETECT,
                        shell(901, "The Martian", "Andy Weir"),
                        List.<String[]>of(new String[]{"900", "A/Anthony, Michael", "Mass Casualties - Michael Anthony (2009).epub",
                                "Michael Anthony"}),
                        List.of("A/Anthony, Michael/Mass Casualties - Michael Anthony (2009) (1).epub",
                                "W/Weir, Andy/The Martian.epub", "W/Weir, Andy/The Martian (1).epub"),
                        Map.of("A/Anthony, Michael/Mass Casualties - Michael Anthony (2009) (1).epub", 900L,
                                "W/Weir, Andy/The Martian.epub", 901L, "W/Weir, Andy/The Martian (1).epub", 901L)),
                new Case("King of a series folder", LibraryOrganizationMode.AUTO_DETECT,
                        shell(700, "King", "Mary Lane"), List.of(),
                        List.of("L/Lane, Mary/King.epub", "L/Lane, Mary/KING - Las Vegas Bad Boys.epub"),
                        Map.of("L/Lane, Mary/King.epub", 700L)),
                new Case("Nico (1) is Nico (Book 1), next to a Nico", LibraryOrganizationMode.AUTO_DETECT,
                        shells(shell(1, "Nico (Book 1)", "Naomi West"), shell(2, "Nico", "Naomi West")), List.of(),
                        List.of("W/Naomi West/Nico (1).epub"),
                        Map.of("W/Naomi West/Nico (1).epub", 1L)),
                new Case("Killing Floor goes with its copy to the book in the folder", LibraryOrganizationMode.AUTO_DETECT,
                        shells(shell(10, "Killing Floor", "Lee Child"), shell(11, "Killing Floor", "Lee Child")),
                        List.<String[]>of(new String[]{"280632", "C/Lee Child/Jack Reacher", "01. Killing Floor - Lee Child (2006).mobi",
                                "Lee Child"}),
                        List.of("C/Lee Child/Jack Reacher/The Killing Floor (1).epub", "C/Lee Child/Jack Reacher/Killing Floor.epub"),
                        Map.of("C/Lee Child/Jack Reacher/The Killing Floor (1).epub", 280632L,
                                "C/Lee Child/Jack Reacher/Killing Floor.epub", 280632L)),
                new Case("The MEG, next to a MEG (1) of another folder", LibraryOrganizationMode.AUTO_DETECT,
                        shell(70, "MEG", "Steve Alten"), List.of(),
                        List.of("A/Steve Alten/MEG/The MEG.epub", "A/Steve Alten/MEG (1).epub"),
                        Map.of("A/Steve Alten/MEG/The MEG.epub", 70L)),
                new Case("The Subtle Knife and its copy, next to a numbered copy", LibraryOrganizationMode.AUTO_DETECT,
                        shellOfSeries(71, "The Subtle Knife", null, "His Dark Materials", 2f, "Philip Pullman"), List.of(),
                        List.of("P/Philip Pullman/His Dark Materials/The Subtle Knife.epub",
                                "P/Philip Pullman/His Dark Materials/The Subtle Knife (1).epub",
                                "P/Philip Pullman/His Dark Materials/02. The Subtle Knife - Philip Pullman (2001) (1).epub"),
                        Map.of("P/Philip Pullman/His Dark Materials/The Subtle Knife.epub", 71L,
                                "P/Philip Pullman/His Dark Materials/The Subtle Knife (1).epub", 71L)),
                new Case("the first volume, next to the book of the title", LibraryOrganizationMode.AUTO_DETECT,
                        shells(shell(10, "Case Histories 1", "Kate Atkinson"), shell(11, "Case Histories", "Kate Atkinson")),
                        List.of(),
                        List.of("A/Kate Atkinson/Case Histories (1).epub"),
                        Map.of("A/Kate Atkinson/Case Histories (1).epub", 10L)),
                new Case("Saga Volume 1, next to a Saga number 2", LibraryOrganizationMode.AUTO_DETECT,
                        shells(shellOfSeries(1, "Saga", "Volume 1", null, null, "Brian K. Vaughan"),
                                shellOfSeries(2, "Saga", null, null, 2f, "Brian K. Vaughan")), List.of(),
                        List.of("V/Brian K. Vaughan/Saga (1).epub"),
                        Map.of("V/Brian K. Vaughan/Saga (1).epub", 1L)),
                new Case("a series reading finds the book its copy reading found first", LibraryOrganizationMode.AUTO_DETECT,
                        shellOfSeries(10, "Case Histories 1", null, null, 1f, "Kate Atkinson"), List.of(),
                        List.of("A/Kate Atkinson/01 - Case Histories (1).epub"),
                        Map.of("A/Kate Atkinson/01 - Case Histories (1).epub", 10L)),
                new Case("BOOK_PER_FOLDER: that series reading, next to a weak match of the folder", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shells(shellOfSeries(10, "Case Histories 1", null, null, 1f, "Kate Atkinson"),
                                shell(20, "Survival", "Kate Atkinson"), shell(21, "Survival", "Kate Atkinson")), List.of(),
                        List.of("A/Kate Atkinson/Books/01 - Case Histories (1).epub", "A/Kate Atkinson/Books/Survival.pdf"),
                        Map.of("A/Kate Atkinson/Books/01 - Case Histories (1).epub", 10L, "A/Kate Atkinson/Books/Survival.pdf", 10L)),
                new Case("a numbered book and its copy", LibraryOrganizationMode.AUTO_DETECT,
                        shellOfSeries(30, "The Long Night", null, null, 3f, "Jane Doe"), List.of(),
                        List.of("D/Doe, Jane/03. The Long Night.epub", "D/Doe, Jane/03. The Long Night (1).epub"),
                        Map.of("D/Doe, Jane/03. The Long Night.epub", 30L, "D/Doe, Jane/03. The Long Night (1).epub", 30L)),
                new Case("another volume in the folder", LibraryOrganizationMode.AUTO_DETECT,
                        List.of(),
                        List.<String[]>of(new String[]{"600", "S/Stuart Slade", "A Dangerous Universe 5.epub", ""}),
                        List.of("S/Stuart Slade/A Dangerous Universe 3.epub", "S/Stuart Slade/A Dangerous Universe 5.pdf"),
                        Map.of("S/Stuart Slade/A Dangerous Universe 5.pdf", 600L)),
                new Case("BOOK_PER_FILE: Dune, next to its copy", LibraryOrganizationMode.BOOK_PER_FILE,
                        shell(300, "Dune", "Frank Herbert"), List.of(),
                        List.of("Frank Herbert/Dune.epub", "Frank Herbert/Dune (1).epub", "Frank Herbert/Dune Messiah.epub"),
                        Map.of("Frank Herbert/Dune.epub", 300L)),
                new Case("BOOK_PER_FOLDER: the book of the folder", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shell(300, "Title", "Author"),
                        List.<String[]>of(new String[]{"100", "Author/Title", "Title.epub", "Author"}),
                        List.of("Author/Title/Title.mobi", "Author/Title/Title (1).pdf"),
                        Map.of("Author/Title/Title.mobi", 100L, "Author/Title/Title (1).pdf", 100L)),
                new Case("BOOK_PER_FOLDER: the fileless book of the folder's first volume", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shells(shell(10, "Survival", "Ann Smith"), shell(11, "Survival", "Ann Smith"),
                                shell(20, "Survival 1", "Ann Smith")), List.of(),
                        List.of("S/Smith, Ann/Survival/Survival 1.epub", "S/Smith, Ann/Survival/Survival.pdf"),
                        Map.of("S/Smith, Ann/Survival/Survival 1.epub", 20L, "S/Smith, Ann/Survival/Survival.pdf", 20L)),
                // What only a " (1)" read as a copy says against the book does not take it from the group
                new Case("BOOK_PER_FOLDER: The Subtle Knife, next to a numbered copy of no number", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shell(71, "The Subtle Knife", "Philip Pullman"), List.of(),
                        List.of("P/Philip Pullman/The Subtle Knife/The Subtle Knife.epub",
                                "P/Philip Pullman/The Subtle Knife/02. The Subtle Knife - Philip Pullman (2001) (1).pdf"),
                        Map.of("P/Philip Pullman/The Subtle Knife/The Subtle Knife.epub", 71L,
                                "P/Philip Pullman/The Subtle Knife/02. The Subtle Knife - Philip Pullman (2001) (1).pdf", 71L)),
                new Case("BOOK_PER_FOLDER: The Subtle Knife number 2, next to a copy numbered 1", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shellOfSeries(71, "The Subtle Knife", null, "His Dark Materials", 2f, "Philip Pullman"), List.of(),
                        List.of("P/Philip Pullman/The Subtle Knife/The Subtle Knife.epub",
                                "P/Philip Pullman/The Subtle Knife/01 - The Subtle Knife (1).pdf"),
                        Map.of("P/Philip Pullman/The Subtle Knife/The Subtle Knife.epub", 71L,
                                "P/Philip Pullman/The Subtle Knife/01 - The Subtle Knife (1).pdf", 71L)),
                new Case("Good Omens, next to a copy by another author", LibraryOrganizationMode.AUTO_DETECT,
                        shell(10, "Good Omens", "Neil Gaiman"), List.of(),
                        List.of("G/Neil Gaiman/Good Omens.epub", "G/Neil Gaiman/Good Omens - T. Pratchett (1).pdf"),
                        Map.of("G/Neil Gaiman/Good Omens.epub", 10L, "G/Neil Gaiman/Good Omens - T. Pratchett (1).pdf", 10L)),
                new Case("BOOK_PER_FOLDER: The Long Night number 1, next to a copy numbered 3", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shells(shellOfSeries(9, "The Long Night", null, null, 1f, "Jane Doe"),
                                shellOfSeries(8, "The Long Night", null, null, 3f, "Jane Doe")), List.of(),
                        List.of("D/Doe, Jane/Night/01. The Long Night.epub", "D/Doe, Jane/Night/03. The Long Night (1).pdf"),
                        Map.of("D/Doe, Jane/Night/01. The Long Night.epub", 9L, "D/Doe, Jane/Night/03. The Long Night (1).pdf", 9L)),
                new Case("BOOK_PER_FOLDER: The Long Night alone, next to a copy numbered 3", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shellOfSeries(9, "The Long Night", null, null, 1f, "Jane Doe"), List.of(),
                        List.of("D/Doe, Jane/Night/The Long Night.epub", "D/Doe, Jane/Night/03. The Long Night (1).pdf"),
                        Map.of("D/Doe, Jane/Night/The Long Night.epub", 9L, "D/Doe, Jane/Night/03. The Long Night (1).pdf", 9L)),
                new Case("BOOK_PER_FOLDER: The Long Night of no number, next to a copy numbered 3", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shell(9, "The Long Night", "Jane Doe"), List.of(),
                        List.of("D/Doe, Jane/Night/The Long Night.epub", "D/Doe, Jane/Night/03. The Long Night (1).pdf"),
                        Map.of("D/Doe, Jane/Night/The Long Night.epub", 9L, "D/Doe, Jane/Night/03. The Long Night (1).pdf", 9L)),
                // A weak match to the group's own book claims it from the folder of the file it follows, as before
                new Case("BOOK_PER_FOLDER: Survival, and its audiobook copy in a subfolder", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shell(20, "Survival", "Kate Atkinson"), List.of(),
                        List.of("A/Kate Atkinson/Survival/Survival.epub", "A/Kate Atkinson/Survival/Audio/Survival (1).m4b"),
                        Map.of("A/Kate Atkinson/Survival/Survival.epub", 20L, "A/Kate Atkinson/Survival/Audio/Survival (1).m4b", 20L)),
                new Case("BOOK_PER_FOLDER: Survival's audiobook copy first", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shell(20, "Survival", "Kate Atkinson"), List.of(),
                        List.of("A/Kate Atkinson/Survival/Audio/Survival (1).m4b", "A/Kate Atkinson/Survival/Survival.epub"),
                        Map.of("A/Kate Atkinson/Survival/Survival.epub", 20L, "A/Kate Atkinson/Survival/Audio/Survival (1).m4b", 20L)),
                new Case("BOOK_PER_FOLDER: The Martian, and its audiobook copy in a subfolder", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shell(10, "The Martian", "Andy Weir"), List.of(),
                        List.of("W/Weir, Andy/The Martian/The Martian.epub", "W/Weir, Andy/The Martian/Audiobook/The Martian (1).m4b"),
                        Map.of("W/Weir, Andy/The Martian/The Martian.epub", 10L,
                                "W/Weir, Andy/The Martian/Audiobook/The Martian (1).m4b", 10L)),
                new Case("BOOK_PER_FOLDER: The Martian, and the parts of its audiobook in a subfolder", LibraryOrganizationMode.BOOK_PER_FOLDER,
                        shell(10, "The Martian", "Andy Weir"), List.of(),
                        List.of("W/Weir, Andy/The Martian/The Martian.epub", "W/Weir, Andy/The Martian/CD/The Martian (1).mp3",
                                "W/Weir, Andy/The Martian/CD/The Martian (2).mp3"),
                        Map.of("W/Weir, Andy/The Martian/The Martian.epub", 10L, "W/Weir, Andy/The Martian/CD/The Martian (1).mp3", 10L,
                                "W/Weir, Andy/The Martian/CD/The Martian (2).mp3", 10L)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void everyFileTheRulesOfBeforeAttachedGoesToTheSameBook(Case c) {
        LibraryPathEntity path = LibraryPathEntity.builder().id(1L).path("/books").build();
        LibraryEntity library = LibraryEntity.builder().id(1L).name("library").organizationMode(c.mode())
                .libraryPaths(new ArrayList<>(List.of(path))).build();
        List<BookFileScanRow> rows = new ArrayList<>();
        List<BookAuthorRow> authors = new ArrayList<>();
        long fileId = 1;
        for (String[] file : c.existing()) {
            long bookId = Long.parseLong(file[0]);
            rows.add(new BookFileScanRow(fileId++, bookId, path.getId(), path.getPath(), file[1], file[2], true,
                    BookFileExtension.fromFileName(file[2]).orElseThrow().getType(), false));
            for (String author : file[3].split("; ")) {
                if (!author.isBlank()) {
                    authors.add(new BookAuthorRow(bookId, author));
                }
            }
        }
        BookRepository books = repository(c.shells(), authors);
        BookGroupingService grouping = new BookGroupingService(books, new FilelessShells(books));
        Map<String, LibraryFile> files = new LinkedHashMap<>();
        for (String newFile : c.newFiles()) {
            int slash = newFile.lastIndexOf('/');
            String name = newFile.substring(slash + 1);
            files.put(newFile, LibraryFile.builder().libraryEntity(library).libraryPathEntity(path)
                    .fileSubPath(slash < 0 ? "" : newFile.substring(0, slash)).fileName(name)
                    .bookFileType(BookFileExtension.fromFileName(name).orElseThrow().getType()).build());
        }

        var result = grouping.groupForRescan(List.copyOf(files.values()), library, new LibraryBookFiles(rows));

        Map<String, Long> now = new LinkedHashMap<>();
        result.filesToAttach().forEach(attachment -> attachment.files().forEach(file -> files.forEach((newFile, f) -> {
            if (f == file) {
                now.put(newFile, attachment.bookId());
            }
        })));
        assertThat(now).containsAllEntriesOf(c.before());
    }

    /** The two reads grouping makes; anything else is a mistake of the test. */
    private static BookRepository repository(List<FilelessBookRow> shells, List<BookAuthorRow> authors) {
        return (BookRepository) Proxy.newProxyInstance(BookRepository.class.getClassLoader(), new Class<?>[]{BookRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findFilelessBookRowsByLibraryId" -> shells;
                    case "findAuthorNamesByBookIds" -> {
                        @SuppressWarnings("unchecked")
                        Collection<Long> ids = (Collection<Long>) args[0];
                        yield authors.stream().filter(row -> ids.contains(row.bookId())).toList();
                    }
                    case "toString" -> "books";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
