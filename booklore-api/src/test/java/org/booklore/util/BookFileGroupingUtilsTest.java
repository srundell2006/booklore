package org.booklore.util;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BookFileGroupingUtilsTest {

    @Test
    void groupByBaseName_shouldGroupFilesWithSameBaseNameInSameDirectory() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("books")
                .fileName("The Hobbit.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile pdf = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("books")
                .fileName("The Hobbit.pdf")
                .bookFileType(BookFileType.PDF)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(epub, pdf));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(epub, pdf);
    }

    @Test
    void groupByBaseName_shouldSeparateFilesInDifferentDirectories() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile file1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("dir1")
                .fileName("book.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("dir2")
                .fileName("book.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(file1, file2));

        assertThat(groups).hasSize(2);
    }

    @Test
    void groupByBaseName_shouldSeparateFilesFromDifferentLibraryPaths() {
        LibraryPathEntity path1 = new LibraryPathEntity();
        path1.setId(1L);
        path1.setPath("/library1");

        LibraryPathEntity path2 = new LibraryPathEntity();
        path2.setId(2L);
        path2.setPath("/library2");

        LibraryFile file1 = LibraryFile.builder()
                .libraryPathEntity(path1)
                .fileSubPath("books")
                .fileName("book.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryPathEntity(path2)
                .fileSubPath("books")
                .fileName("book.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(file1, file2));

        assertThat(groups).hasSize(2);
    }

    @Test
    void groupByBaseName_shouldGroupFormatIndicatorVariants() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("books")
                .fileName("The Hobbit.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile pdf = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("books")
                .fileName("The Hobbit (PDF).pdf")
                .bookFileType(BookFileType.PDF)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(epub, pdf));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(epub, pdf);
    }

    // ========== Grouping Tests ==========

    @Test
    void groupByBaseName_shouldGroupExtendedNamesMatchingFolderName() {
        // The "American Gods" case - files with extended names should group with folder
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("American Gods")
                .fileName("American Gods.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile mobi = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("American Gods")
                .fileName("American Gods.mobi")
                .bookFileType(BookFileType.MOBI)
                .build();

        LibraryFile m4b = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("American Gods")
                .fileName("American Gods The Tenth Anniversary Edition (A Full Cast Production).m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(epub, mobi, m4b));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(epub, mobi, m4b);
    }

    @Test
    void groupByBaseName_shouldGroupEditionVariantsWithFolderName() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile regular = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("The Hobbit")
                .fileName("The Hobbit.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile anniversary = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("The Hobbit")
                .fileName("The Hobbit 50th Anniversary Edition.pdf")
                .bookFileType(BookFileType.PDF)
                .build();

        LibraryFile unabridged = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("The Hobbit")
                .fileName("The Hobbit Unabridged.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(regular, anniversary, unabridged));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(regular, anniversary, unabridged);
    }

    @Test
    void groupByBaseName_shouldKeepSeriesEntriesSeparate() {
        // Harry Potter series should remain as separate books
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile book1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Harry Potter")
                .fileName("Harry Potter Book 1.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile book2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Harry Potter")
                .fileName("Harry Potter Book 2.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile book3 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Harry Potter")
                .fileName("Harry Potter Book 3.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(book1, book2, book3));

        assertThat(groups).hasSize(3);
    }

    @Test
    void groupByBaseName_shouldKeepSeriesWithNumbersSeparate() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile vol1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Lord of the Rings")
                .fileName("Lord of the Rings Vol 1.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile vol2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Lord of the Rings")
                .fileName("Lord of the Rings Vol 2.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(vol1, vol2));

        assertThat(groups).hasSize(2);
    }

    @Test
    void groupByBaseName_shouldGroupSeriesFormatsWithSameNumber() {
        // Same series entry in different formats should group
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile book1epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Harry Potter")
                .fileName("Harry Potter Book 1.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile book1pdf = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Harry Potter")
                .fileName("Harry Potter Book 1.pdf")
                .bookFileType(BookFileType.PDF)
                .build();

        LibraryFile book2epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Harry Potter")
                .fileName("Harry Potter Book 2.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(book1epub, book1pdf, book2epub));

        assertThat(groups).hasSize(2);
        // Book 1 should have 2 files
        boolean foundBook1Group = groups.values().stream()
                .anyMatch(list -> list.size() == 2 && list.containsAll(List.of(book1epub, book1pdf)));
        assertThat(foundBook1Group).isTrue();
    }

    @Test
    void groupByBaseName_shouldSeparateUnrelatedBooksInAuthorFolder() {
        // Multiple different books by same author should stay separate
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile americanGods = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman")
                .fileName("American Gods.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile coraline = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman")
                .fileName("Coraline.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile stardust = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman")
                .fileName("Stardust.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(americanGods, coraline, stardust));

        assertThat(groups).hasSize(3);
    }

    @Test
    void groupByBaseName_shouldUseExactMatchingForRootLevelFiles() {
        // Files at root level (no subfolder) should use exact key matching
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath(null)
                .fileName("The Hobbit.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile pdf = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath(null)
                .fileName("The Hobbit.pdf")
                .bookFileType(BookFileType.PDF)
                .build();

        LibraryFile other = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath(null)
                .fileName("1984.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(epub, pdf, other));

        assertThat(groups).hasSize(2);
        // Hobbit files should be grouped
        boolean foundHobbitGroup = groups.values().stream()
                .anyMatch(list -> list.size() == 2 && list.containsAll(List.of(epub, pdf)));
        assertThat(foundHobbitGroup).isTrue();
    }

    @Test
    void groupByBaseName_shouldGroupFilesWithAuthorVariationsMatchingFolder() {
        // The author folder vouches for "Neil Gaiman" being the author's name rather than part of the title
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile basic = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman/American Gods")
                .fileName("American Gods.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile withAuthor = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman/American Gods")
                .fileName("American Gods - Neil Gaiman.mobi")
                .bookFileType(BookFileType.MOBI)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(basic, withAuthor));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(basic, withAuthor);
    }

    @Test
    void groupByBaseName_shouldKeepANameSuffixNothingVouchesForAsPartOfTheTitle() {
        // In a folder named only after the title, " - Neil Gaiman" reads like " - Las Vegas Bad Boys" or " - Karachi":
        // nothing says it is an author, so the files stay two books rather than risk merging two titles
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile basic = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("American Gods")
                .fileName("American Gods.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile withAuthor = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("American Gods")
                .fileName("American Gods - Neil Gaiman.mobi")
                .bookFileType(BookFileType.MOBI)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(basic, withAuthor));

        assertThat(groups).hasSize(2);
    }

    // ========== Bare-Number Series Detection Tests ==========

    @Test
    void groupByBaseName_shouldKeepBarePartSeriesEntriesSeparate() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile part1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Harry Potter")
                .fileName("Harry Potter Part 1.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile part2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Harry Potter")
                .fileName("Harry Potter Part 2.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(part1, part2));

        assertThat(groups).hasSize(2);
    }

    @Test
    void groupByBaseName_shouldSeparateBareNumberedSeriesFiles() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile file1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Percy Jackson")
                .fileName("1. The Lightning Thief.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Percy Jackson")
                .fileName("2. The Sea of Monsters.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(file1, file2));

        assertThat(groups).hasSize(2);
    }

    @Test
    void groupByBaseName_shouldGroupMultiPartFilesOfSameBook() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile part1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus")
                .fileName("1. The Lost Hero (part 1).m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile part2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus")
                .fileName("1. The Lost Hero (part 2).m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(part1, part2));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(part1, part2);
    }

    @Test
    void groupByBaseName_shouldHandleMixedPartsAndSeparateBooks() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile lostHeroPt1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus")
                .fileName("1. The Lost Hero (part 1).m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile lostHeroPt2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus")
                .fileName("1. The Lost Hero (part 2).m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile sonOfNeptune = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus")
                .fileName("2. The Son of Neptune.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(lostHeroPt1, lostHeroPt2, sonOfNeptune));

        assertThat(groups).hasSize(2);
        boolean foundLostHeroGroup = groups.values().stream()
                .anyMatch(list -> list.size() == 2 && list.containsAll(List.of(lostHeroPt1, lostHeroPt2)));
        assertThat(foundLostHeroGroup).isTrue();
    }

    @Test
    void groupByBaseName_shouldHandleFolderBasedAudiobook() {
        // Folder-based audiobook (MP3 folder) with ebook files
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("American Gods")
                .fileName("American Gods.epub")
                .bookFileType(BookFileType.EPUB)
                .folderBased(false)
                .build();

        // Folder-based audiobook appears as "American Gods" subfolder
        LibraryFile mp3Folder = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("American Gods")
                .fileName("American Gods")
                .bookFileType(BookFileType.AUDIOBOOK)
                .folderBased(true)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(epub, mp3Folder));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(epub, mp3Folder);
    }

    @Test
    void groupByBaseName_shouldHandleNestedFolderPath() {
        // Files in nested folder like "Neil Gaiman/American Gods"
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman/American Gods")
                .fileName("American Gods.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile m4b = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman/American Gods")
                .fileName("American Gods 10th Anniversary.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(epub, m4b));

        // Should use last folder component "American Gods" as reference
        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(epub, m4b);
    }

    @Test
    void groupByBaseName_shouldGroupNestedAudiobookFolderWithEbooks() {
        // Nested audiobook folder should group with ebooks in same parent folder
        // Book Folder/
        // ├── Book.pdf
        // ├── Book.epub
        // └── Book Audiobook/  (folder-based audiobook)
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile pdf = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Book Folder")
                .fileName("Book.pdf")
                .bookFileType(BookFileType.PDF)
                .build();

        LibraryFile epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Book Folder")
                .fileName("Book.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        // Folder-based audiobook appears as a file with folderBased=true
        LibraryFile audiobookFolder = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Book Folder")
                .fileName("Book Audiobook")
                .bookFileType(BookFileType.AUDIOBOOK)
                .folderBased(true)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(pdf, epub, audiobookFolder));

        // All should be grouped together
        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(pdf, epub, audiobookFolder);
    }

    @Test
    void groupByBaseName_shouldGroupAudiobookVariantNames() {
        // Different audiobook naming conventions should all group
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("The Hobbit")
                .fileName("The Hobbit.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile audiobook1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("The Hobbit")
                .fileName("The Hobbit Audiobook")
                .bookFileType(BookFileType.AUDIOBOOK)
                .folderBased(true)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(epub, audiobook1));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(epub, audiobook1);
    }

    @Test
    void groupByBaseName_shouldSeparateFiveBookSeriesWithBareNumbers() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        List<LibraryFile> files = new java.util.ArrayList<>();
        String[] titles = {
                "1. The Lightning Thief.m4b",
                "2. The Sea of Monsters.m4b",
                "3. The Titans Curse.m4b",
                "4. The Battle of the Labyrinth.m4b",
                "5. The Last Olympian.m4b"
        };
        for (String title : titles) {
            files.add(LibraryFile.builder()
                    .libraryPathEntity(pathEntity)
                    .fileSubPath("Percy Jackson")
                    .fileName(title)
                    .bookFileType(BookFileType.AUDIOBOOK)
                    .build());
        }

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(files);

        assertThat(groups).hasSize(5);
    }

    @Test
    void groupByBaseName_shouldGroupSameBareNumberInMultipleFormats() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Percy Jackson")
                .fileName("1. The Lightning Thief.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile m4b = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Percy Jackson")
                .fileName("1. The Lightning Thief.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile epub2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Percy Jackson")
                .fileName("2. The Sea of Monsters.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(epub, m4b, epub2));

        // Book 1 in 2 formats = 1 group, Book 2 = 1 group
        assertThat(groups).hasSize(2);
        boolean foundBook1Group = groups.values().stream()
                .anyMatch(list -> list.size() == 2 && list.containsAll(List.of(epub, m4b)));
        assertThat(foundBook1Group).isTrue();
    }

    @Test
    void groupByBaseName_shouldSeparateDashPrefixedSeriesFiles() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile file1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Rick Riordan")
                .fileName("01 - The Lightning Thief.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Rick Riordan")
                .fileName("02 - The Sea of Monsters.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(file1, file2));

        assertThat(groups).hasSize(2);
    }

    @Test
    void groupByBaseName_shouldHandleRootLevelBareNumberedFiles() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile file1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath(null)
                .fileName("1. The Lightning Thief.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath(null)
                .fileName("2. The Sea of Monsters.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(file1, file2));

        // Root-level uses exact key matching, different keys = separate groups
        assertThat(groups).hasSize(2);
    }

    // ========== Series Detection with Various Keywords ==========

    @Test
    void groupByBaseName_shouldSeparateEpisodeSeriesEntries() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile ep1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("The Podcast")
                .fileName("The Podcast Episode 1.mp3")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile ep2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("The Podcast")
                .fileName("The Podcast Episode 2.mp3")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(ep1, ep2));

        assertThat(groups).hasSize(2);
    }

    @Test
    void groupByBaseName_shouldSeparateHashNumberedSeries() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile n1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Discworld")
                .fileName("Discworld #1.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile n2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Discworld")
                .fileName("Discworld #2.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(n1, n2));

        assertThat(groups).hasSize(2);
    }

    // ========== Multi-Part Grouping Edge Cases ==========

    @Test
    void groupByBaseName_shouldGroupBracketedPartsAtRootLevel() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile pt1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath(null)
                .fileName("The Lost Hero (part 1).m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile pt2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath(null)
                .fileName("The Lost Hero (part 2).m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(pt1, pt2));

        // Root-level exact key matching: both produce "the lost hero" → same group
        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(pt1, pt2);
    }

    @Test
    void groupByBaseName_shouldGroupDiscVariantsOfSameBook() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile d1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("War and Peace")
                .fileName("War and Peace disc 1.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile d2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("War and Peace")
                .fileName("War and Peace disc 2.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile d3 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("War and Peace")
                .fileName("War and Peace disc 3.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(d1, d2, d3));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(d1, d2, d3);
    }

    @Test
    void groupByBaseName_shouldGroupCdVariantsOfSameBook() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile cd1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Moby Dick")
                .fileName("Moby Dick cd 1.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        LibraryFile cd2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Moby Dick")
                .fileName("Moby Dick cd 2.m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(cd1, cd2));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(cd1, cd2);
    }

    // ========== Grouping Boundary / Misc Edge Cases ==========

    @Test
    void groupByBaseName_shouldHandleEmptyList() {
        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of());
        assertThat(groups).isEmpty();
    }

    @Test
    void groupByBaseName_shouldHandleSingleFileInFolder() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile file = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Some Folder")
                .fileName("Lonely Book.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(file));

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactly(file);
    }

    @Test
    void groupByBaseName_shouldHandleEmptySubPath() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile file1 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("Book.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("Book.pdf")
                .bookFileType(BookFileType.PDF)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(file1, file2));

        // Empty subPath treated as root level
        assertThat(groups).hasSize(1);
    }

    @Test
    void groupByBaseName_shouldSeparateCompletelyUnrelatedFilesInFolder() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile war = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Random")
                .fileName("War and Peace.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile moby = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Random")
                .fileName("Moby Dick.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile pride = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Random")
                .fileName("Pride and Prejudice.pdf")
                .bookFileType(BookFileType.PDF)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(war, moby, pride));

        assertThat(groups).hasSize(3);
    }

    @Test
    void groupByBaseName_shouldHandleFullHeroesOfOlympusScenario() {
        // Realistic 5-book series with multi-part splits for some books
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile lh1 = LibraryFile.builder().libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus").fileName("1. The Lost Hero (part 1).m4b").bookFileType(BookFileType.AUDIOBOOK).build();
        LibraryFile lh2 = LibraryFile.builder().libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus").fileName("1. The Lost Hero (part 2).m4b").bookFileType(BookFileType.AUDIOBOOK).build();
        LibraryFile son = LibraryFile.builder().libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus").fileName("2. The Son of Neptune.m4b").bookFileType(BookFileType.AUDIOBOOK).build();
        LibraryFile moa1 = LibraryFile.builder().libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus").fileName("3. The Mark of Athena (part 1).m4b").bookFileType(BookFileType.AUDIOBOOK).build();
        LibraryFile moa2 = LibraryFile.builder().libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus").fileName("3. The Mark of Athena (part 2).m4b").bookFileType(BookFileType.AUDIOBOOK).build();
        LibraryFile hoh = LibraryFile.builder().libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus").fileName("4. The House of Hades.m4b").bookFileType(BookFileType.AUDIOBOOK).build();
        LibraryFile boo = LibraryFile.builder().libraryPathEntity(pathEntity)
                .fileSubPath("Heroes of Olympus").fileName("5. The Blood of Olympus.m4b").bookFileType(BookFileType.AUDIOBOOK).build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(lh1, lh2, son, moa1, moa2, hoh, boo));

        // 5 books: Lost Hero (2 parts grouped), Son of Neptune, Mark of Athena (2 parts grouped), House of Hades, Blood of Olympus
        assertThat(groups).hasSize(5);
        boolean lhGrouped = groups.values().stream().anyMatch(l -> l.size() == 2 && l.containsAll(List.of(lh1, lh2)));
        boolean moaGrouped = groups.values().stream().anyMatch(l -> l.size() == 2 && l.containsAll(List.of(moa1, moa2)));
        assertThat(lhGrouped).isTrue();
        assertThat(moaGrouped).isTrue();
    }

    @Test
    void groupByBaseName_shouldGroupFolderWithDotInName() {
        // Folder-based audiobook with dot in name like "1. American Gods - Neil Gaiman (2011)"
        // should group with ebooks in the same folder
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");

        LibraryFile epub = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman/American Gods")
                .fileName("1. American Gods - Neil Gaiman (2011).epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile mobi = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman/American Gods")
                .fileName("1. American Gods - Neil Gaiman (2011).mobi")
                .bookFileType(BookFileType.MOBI)
                .build();

        LibraryFile m4b = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman/American Gods")
                .fileName("1. American Gods - Neil Gaiman (2011).m4b")
                .bookFileType(BookFileType.AUDIOBOOK)
                .build();

        // Folder-based audiobook - note: fileName is folder name without extension
        LibraryFile mp3Folder = LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileSubPath("Neil Gaiman/American Gods")
                .fileName("1. American Gods - Neil Gaiman (2011)")
                .bookFileType(BookFileType.AUDIOBOOK)
                .folderBased(true)
                .build();

        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(epub, mobi, m4b, mp3Folder));

        // All 4 files should be grouped as one book
        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).containsExactlyInAnyOrder(epub, mobi, m4b, mp3Folder);
    }

    // ========== Exact keys: what the production rescan merged, and what must stay one book ==========

    private static final LibraryPathEntity LIBRARY_PATH = libraryPath();

    private static LibraryPathEntity libraryPath() {
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(1L);
        pathEntity.setPath("/library");
        return pathEntity;
    }

    /** A new file in {@code folder}; a name without a book extension is a folder audiobook. */
    private static LibraryFile file(String folder, String name) {
        var extension = BookFileExtension.fromFileName(name);
        return LibraryFile.builder()
                .libraryPathEntity(LIBRARY_PATH)
                .fileSubPath(folder)
                .fileName(name)
                .bookFileType(extension.map(BookFileExtension::getType).orElse(BookFileType.AUDIOBOOK))
                .folderBased(extension.isEmpty())
                .build();
    }

    private static List<List<String>> partition(String folder, String... names) {
        List<LibraryFile> files = Arrays.stream(names).map(name -> file(folder, name)).toList();
        return BookFileGroupingUtils.groupByBaseName(files).values().stream()
                .map(group -> group.stream().map(LibraryFile::getFileName).toList())
                .toList();
    }

    @Nested
    class ExactKeys {

        @Test
        void keepsEachTitleByTheFoldersAuthorApart() {
            // Production: "Shadow Command" and "Warrior Class" were attached to "Battle Born" because the folder's
            // name was a substring of every file's name
            Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(
                    file("B/Dale Brown", "Battle Born by Dale Brown.epub"),
                    file("B/Dale Brown", "Shadow Command by Dale Brown.epub"),
                    file("B/Dale Brown", "Warrior Class by Dale Brown.epub")));

            assertThat(groups.keySet()).containsExactly(
                    "1:B/Dale Brown:battle born", "1:B/Dale Brown:shadow command", "1:B/Dale Brown:warrior class");
        }

        @Test
        void keepsTitlesThatShareTheirFirstPartApart() {
            // Production: " - Karachi" and " - Stella Marni" were read as authors and dropped, leaving "assignment"
            assertThat(partition("A/Aarons, Edward S",
                    "Assignment - Karachi.epub", "Assignment - Karachi.mobi", "Assignment - Stella Marni.epub"))
                    .containsExactlyInAnyOrder(
                            List.of("Assignment - Karachi.epub", "Assignment - Karachi.mobi"),
                            List.of("Assignment - Stella Marni.epub"));
        }

        @Test
        void keepsTheBooksOfASeriesFolderApart() {
            assertThat(partition("A/Alex Archer/Rogue Angel",
                    "Rogue Angel - Destiny.epub", "Rogue Angel - Solomon's Jar.epub", "Rogue Angel - The Chosen.epub"))
                    .hasSize(3);
            assertThat(partition("Rogue Angel",
                    "Rogue Angel - Destiny.epub", "Rogue Angel - Solomon's Jar.epub", "Rogue Angel - Destiny.pdf"))
                    .containsExactlyInAnyOrder(
                            List.of("Rogue Angel - Destiny.epub", "Rogue Angel - Destiny.pdf"),
                            List.of("Rogue Angel - Solomon's Jar.epub"));
        }

        @Test
        void keepsTheFilesOfOneBaseNameTogetherWhateverTheirNamesReadAs() {
            // An ebook's "(Part 1)" is a volume and an audiobook's a split file, but the two files share one sidecar
            // name, so as two books they would overwrite each other's metadata and cover
            assertThat(partition("Rick Riordan",
                    "The Lost Hero (Part 1).epub", "The Lost Hero (Part 1).m4b", "The Lost Hero (Part 2).m4b"))
                    .containsExactly(List.of("The Lost Hero (Part 1).epub", "The Lost Hero (Part 1).m4b", "The Lost Hero (Part 2).m4b"));
        }

        @Test
        void splitsATitleByDifferentYearsAndLeavesAFileNamingNoneApart() {
            assertThat(partition("Frank Herbert", "Dune (1965).epub", "Dune (2021).epub", "Dune.pdf")).hasSize(3);
            assertThat(partition("Frank Herbert", "Dune (1965).epub", "Dune.pdf", "Dune (1965).mobi"))
                    .containsExactly(List.of("Dune (1965).epub", "Dune.pdf", "Dune (1965).mobi"));
        }

        @Test
        void foldsADuplicateOnlyIntoTheFileItDuplicates() {
            assertThat(partition("Terry Pratchett", "Mort.epub", "Mort (1).epub", "Mort - Copy.epub", "Mort (copy).pdf"))
                    .containsExactly(List.of("Mort.epub", "Mort (1).epub", "Mort - Copy.epub", "Mort (copy).pdf"));
            assertThat(partition("Comics/Saga", "Saga (1).cbz", "Saga (2).cbz")).hasSize(2);
            assertThat(partition("Comics/Saga", "Saga (2).cbz", "Saga 2.cbr"))
                    .containsExactly(List.of("Saga (2).cbz", "Saga 2.cbr"));
        }

        @Test
        void groupsANameWithANumberTooLongForAPartWithoutThrowing() {
            assertThat(partition("Tolkien", "The Hobbit (Part 9780261102217).m4b", "The Hobbit.epub")).hasSize(2);
            assertThat(partition("Misc", "Interview cd 20231015120000.mp3")).hasSize(1);
        }

        @Test
        void keysEveryGroupByLibraryPathFolderAndIdentity() {
            Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(List.of(
                    file(null, "01 - The Lightning Thief.m4b"),
                    file("", "01 - The Lost Hero.m4b"),
                    file("Comics/Saga", "(Digital).cbz")));

            assertThat(groups.keySet()).containsExactly(
                    "1::lightning thief #1", "1::lost hero #1", "1:Comics/Saga:=(digital)");
        }

        // groups separated by ';', the files of one group by '+'
        @ParameterizedTest(name = "[{0}] {1}")
        @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
                B/Dale Brown                  | Battle Born by Dale Brown.epub ; Shadow Command by Dale Brown.epub ; Warrior Class by Dale Brown.epub
                B/Brown, Dale                 | Battle Born by Dale Brown.epub + Battle Born.mobi ; Shadow Command by Dale Brown.epub
                B/Dale Brown                  | Battle Born by Dale Brown.epub + Battle Born by Dale Brown - Unabridged.m4b ; Shadow Command by Dale Brown.epub
                ""                            | Dale Brown - Battle Born.epub ; Dale Brown - Shadow Command.epub
                Dale Brown                    | Dale Brown - Battle Born.epub ; Dale Brown - Shadow Command.epub
                A/Aarons, Edward S            | Assignment - Karachi.epub ; Assignment - Stella Marni.epub ; Assignment - The Cairo Dancers.epub
                Some Author                   | KING - Las Vegas Bad Boys.epub ; King.epub
                Stuart Slade                  | A Dangerous Universe 3.epub ; A Dangerous Universe 5.epub
                Terry Pratchett/Discworld     | Discworld 01 - The Colour of Magic.epub ; Discworld 02 - The Light Fantastic.epub + Discworld 02 - The Light Fantastic.mobi
                Terry Pratchett/Discworld     | [Discworld 03] Equal Rites.epub ; [Discworld 04] Mort.epub + [Discworld 04] Mort.pdf
                Brandon Sanderson/Mistborn    | Mistborn 1 - The Final Empire.epub ; Mistborn 2 - The Well of Ascension.epub ; Mistborn 3 - The Hero of Ages.epub
                James S. A. Corey/The Expanse | The Expanse - Book One.epub ; The Expanse - Book Two.epub ; The Expanse - Book Three.epub
                Comics/Saga                   | Saga 001 (2012) (Digital).cbz ; Saga 002 (2012) (Digital).cbz ; Saga 003 (2012) (Digital).cbz
                Stephen King/The Dark Tower   | The Dark Tower I - The Gunslinger.epub ; The Dark Tower II - The Drawing of the Three.epub ; The Dark Tower III - The Waste Lands.epub
                Rocky                         | Rocky II.epub ; Rocky III.epub
                Frank Herbert                 | Dune.epub + Dune.pdf ; Dune Messiah.epub ; Children of Dune.epub
                Stephen King                  | It.epub ; The Institute.epub ; Misery.epub
                Isaac Asimov                  | Foundation.epub + Foundation.mobi ; Foundation and Empire.epub ; Second Foundation.epub
                Light Novels                  | Kokoro Connect Volume - Nise Random.epub ; Kokoro Connect Volume 2 - Kizu Random.epub ; Kokoro Connect Volume 4 - Michi Random.epub
                Jeffrey Archer                | A Quiver Full of Arrows.epub ; A Quiver Full of Arrows - Stories.epub ; Kane and Abel.epub
                Michael Anthony               | Mass Casualties - Michael Anthony (2009).epub + Mass Casualties - Michael Anthony (2009) (1).epub
                J.R.R. Tolkien                | The Hobbit.epub + Hobbit, The.pdf + The Hobbit - J.R.R. Tolkien.mobi + Tolkien, J.R.R. - The Hobbit.azw3 ; The Silmarillion.epub
                Stephen King/The Stand        | The Stand.epub + The Stand Part 01.m4b + The Stand Part 02.m4b
                Neil Gaiman/American Gods     | American Gods.epub + American Gods.mobi + American Gods The Tenth Anniversary Edition (A Full Cast Production).m4b
                The Hobbit                    | The Hobbit.epub + The Hobbit Unabridged.m4b + The Hobbit (Illustrated Edition).pdf
                Heroes of Olympus             | 1. The Lost Hero (part 1).m4b + 1. The Lost Hero (part 2).m4b ; 2. The Son of Neptune.m4b ; 3. The Mark of Athena (part 1).m4b + 3. The Mark of Athena (part 2).m4b ; 4. The House of Hades.m4b ; 5. The Blood of Olympus.m4b
                Book Folder                   | Book.pdf + Book.epub + Book Audiobook
                Robert Jordan                 | The Great Hunt.epub + The Great Hunt (1).epub ; The Dragon Reborn.epub
                Comics/Saga                   | Saga (1).cbz ; Saga (2).cbz
                ""                            | 01 - The Lightning Thief.m4b ; 01 - The Lost Hero.m4b
                War and Peace                 | War and Peace disc 1.m4b + War and Peace disc 2.m4b + War and Peace disc 3.m4b
                Ray Bradbury                  | Fahrenheit 451.epub + Fahrenheit 451.mobi ; The Martian Chronicles.epub
                George Orwell                 | 1984.epub + 1984 - George Orwell.pdf ; Animal Farm.epub
                Arthur C. Clarke              | 2001 A Space Odyssey.epub ; 2010 Odyssey Two.epub
                Poetry                        | Collected Poems.epub ; Collected Poems - W. B. Yeats.epub + Collected Poems - W. B. Yeats.pdf ; Collected Poems - T. S. Eliot.epub
                """)
        void groupsAFoldersFilesOnlyByEqualKeys(String folder, String expected) {
            List<List<String>> want = Arrays.stream(expected.split(";"))
                    .map(group -> Arrays.stream(group.split("\\+")).map(String::trim).toList())
                    .toList();

            assertThat(partition(folder, want.stream().flatMap(List::stream).toArray(String[]::new)))
                    .containsExactlyInAnyOrderElementsOf(want);
        }

        // Two new files of one folder: one book or two, whichever order they come in
        @ParameterizedTest(name = "[{0}] {1} ~ {2}: {3}")
        @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
                Stuart Slade                  | A Dangerous Universe 3.epub                       | A Dangerous Universe 5.epub                        | DIFF
                Kokoro Connect                | Kokoro Connect Volume 4 - Michi Random.epub       | Kokoro Connect Volume - Nise Random.epub           | DIFF
                Some Author                   | KING - Las Vegas Bad Boys.epub                    | King.epub                                          | DIFF
                Las Vegas Bad Boys            | KING - Las Vegas Bad Boys.epub                    | King.mobi                                          | SAME
                B/Dale Brown                  | Battle Born by Dale Brown.epub                    | Shadow Command by Dale Brown.epub                  | DIFF
                A/Aarons, Edward S            | Assignment - Karachi.epub                         | Assignment - Treason.epub                          | DIFF
                A/Aarons, Edward S/Sam Durell | Assignment - Maltese Maiden.epub                  | Assignment - Stella Marni.epub                     | DIFF
                ""                            | 01 - The Lightning Thief.m4b                      | 01 - The Lost Hero.m4b                             | DIFF
                Jeffrey Archer                | A Quiver Full of Arrows - Stories.epub            | A Quiver Full of Arrows.epub                       | DIFF
                Michael Anthony               | Mass Casualties - Michael Anthony (2009) (1).epub | Mass Casualties - Michael Anthony (2009).epub      | SAME
                Harry Potter                  | Harry Potter Book 1.epub                          | Harry Potter Book 2.epub                           | DIFF
                Harry Potter                  | Harry Potter Book 1.epub                          | Harry Potter 1.mobi                                | SAME
                Harry Potter                  | Harry Potter Part 1.epub                          | Harry Potter Part 2.epub                           | DIFF
                The Expanse                   | The Expanse - Book One.epub                       | The Expanse - Book Two.epub                        | DIFF
                The Expanse                   | The Expanse - Book One.epub                       | The Expanse Book 1.pdf                             | SAME
                Mistborn                      | Mistborn 2 - The Well of Ascension.epub           | Mistborn 3 - The Hero of Ages.epub                 | DIFF
                Saga                          | Saga Vol. 1.cbz                                   | Saga Vol. 2.cbz                                    | DIFF
                Saga                          | Saga Vol. 1.cbz                                   | Saga Volume 1.cbr                                  | SAME
                Saga                          | Saga v01.cbz                                      | Saga Vol. 1.cbr                                    | SAME
                Saga                          | Saga 001 (2012) (Digital).cbz                     | Saga 001 (2012).cbr                                | SAME
                Saga                          | Saga #12.cbz                                      | Saga #13.cbz                                       | DIFF
                Comics/Saga                   | Saga Vol. 1.cbz                                   | Saga 001.cbz                                       | DIFF
                Comics/Saga                   | Saga v01 (2012) (Digital).cbz                     | Saga 001 (2012) (Digital).cbz                      | DIFF
                Comics/Saga                   | Saga #1.cbz                                       | Saga 001.cbz                                       | SAME
                Comics/Saga                   | Saga Book One.cbz                                 | Saga #1.cbr                                        | DIFF
                Comics/Batman                 | Batman 001.cbz                                    | Batman Vol. 1.cbz                                  | DIFF
                Comics/Saga                   | Saga Volume 1 (Deluxe Edition).cbz                | Saga Volume 1.cbz                                  | DIFF
                Comics/Saga                   | Saga Volume 1 (Deluxe Edition).cbz                | Saga Volume 1 (Deluxe Edition).pdf                 | SAME
                Star Wars                     | Star Wars Episode IV - A New Hope.epub            | Star Wars Episode V - The Empire Strikes Back.epub | DIFF
                Shakespeare                   | Henry IV Part 1.epub                              | Henry IV Part 2.epub                               | DIFF
                Jack Reacher                  | Jack Reacher 01 - Killing Floor.epub              | Jack Reacher 02 - Die Trying.epub                  | DIFF
                Percy Jackson                 | 1. The Lightning Thief.epub                       | 1. The Lightning Thief.m4b                         | SAME
                Joseph Heller                 | Catch-22.epub                                     | Catch-22.azw3                                      | SAME
                Frank Herbert                 | Dune.epub                                         | Dune Messiah.epub                                  | DIFF
                Frank Herbert                 | Dune.epub                                         | Children of Dune.epub                              | DIFF
                Stephen King                  | It.epub                                           | The Institute.epub                                 | DIFF
                Isaac Asimov                  | Foundation.epub                                   | Second Foundation.epub                             | DIFF
                Stieg Larsson                 | The Girl with the Dragon Tattoo.epub              | The Girl Who Played with Fire.epub                 | DIFF
                Tolkien                       | The Lord of the Rings - The Fellowship of the Ring.epub | The Lord of the Rings - The Two Towers.epub  | DIFF
                Ann Leckie                    | Ancillary Justice.epub                            | Ancillary Sword.epub                               | DIFF
                Misc                          | The Hobbit - J.R.R. Tolkien.epub                  | The Hobbit.azw3                                    | SAME
                Poetry                        | Collected Poems - W. B. Yeats.epub                | Collected Poems - T. S. Eliot.epub                 | DIFF
                ""                            | Collected Poems - W. B. Yeats.epub                | Collected Poems - T. S. Eliot.epub                 | DIFF
                ""                            | Collected Poems by W.B. Yeats.epub                | Collected Poems by T.S. Eliot.epub                 | DIFF
                ""                            | Dune - F. Herbert.epub                            | Dune - B. Herbert.epub                             | DIFF
                Anthologies                   | Short Stories - Philip K. Dick.epub               | Short Stories - Robert E. Howard.epub              | DIFF
                Manuals                       | Field Manual - U.S. Army.pdf                      | Field Manual - U.S. Navy.pdf                       | DIFF
                Poetry                        | Collected Poems - T. S. Eliot.epub                | Collected Poems - T.S. Eliot.pdf                   | SAME
                Poetry                        | Collected Poems - T. S. Eliot.epub                | Collected Poems.pdf                                | SAME
                ""                            | Dune - F. Herbert.epub                            | Dune - Frank P. Herbert.pdf                        | SAME
                Neil Gaiman/American Gods     | American Gods - Neil Gaiman.mobi                  | American Gods.epub                                 | SAME
                American Gods                 | American Gods - Neil Gaiman.mobi                  | American Gods.epub                                 | DIFF
                Star Trek                     | Star Trek - Prime Directive.epub                  | Star Trek - The Final Reflection.epub              | DIFF
                Star Trek                     | Star Trek - Prime Directive.epub                  | Star Trek.epub                                     | DIFF
                Misc                          | Harry Potter and the Sorcerer's Stone.epub        | Harry Potter and the Sorcerers Stone.pdf           | SAME
                Misc                          | Sapiens: A Brief History of Humankind.epub        | Sapiens - A Brief History of Humankind.pdf         | SAME
                Misc                          | Sapiens - A Brief History of Humankind.epub       | Sapiens.pdf                                        | DIFF
                Misc                          | Les Misérables.epub                               | Les Miserables.pdf                                 | SAME
                Misc                          | Pride & Prejudice.epub                            | Pride and Prejudice.mobi                           | SAME
                Misc                          | The_Name_of_the_Wind.epub                         | The Name of the Wind.pdf                           | SAME
                Misc                          | Stand by Me.epub                                  | Stand.epub                                         | DIFF
                William Gibson                | Neuromancer 20th Anniversary Edition.epub         | Neuromancer.mobi                                   | SAME
                Stephen King                  | The Stand (Complete and Uncut Edition).epub       | The Stand.epub                                     | SAME
                Misc                          | Book (Extended Edition).pdf                       | Book.epub                                          | SAME
                Misc                          | The Martian (v5.0).epub                           | The Martian.mobi                                   | SAME
                Misc                          | Special Forces.epub                               | Forces.epub                                        | DIFF
                Misc                          | The Complete Sherlock Holmes.epub                 | Sherlock Holmes.epub                               | DIFF
                Misc                          | Dune (Graphic Novel).cbz                          | Dune.epub                                          | DIFF
                Terry Pratchett               | Mort (1).epub                                     | Mort.epub                                          | SAME
                Terry Pratchett               | Mort - Copy.epub                                  | Mort.epub                                          | SAME
                Stephen King                  | The Stand Part 01.m4b                             | The Stand Part 02.m4b                              | SAME
                Stephen King                  | The Stand (Part 1).m4b                            | The Stand (Part 2).m4b                             | SAME
                Stephen King                  | The Stand - Part 1 of 3.m4b                       | The Stand - Part 2 of 3.m4b                        | SAME
                Stephen King                  | The Stand Disc 1.mp3                              | The Stand.epub                                     | SAME
                Tolkien/The Hobbit            | CD1                                               | CD2                                                | SAME
                Tolkien/The Hobbit            | Disc 1                                            | Disc 2                                             | SAME
                Tolkien/The Hobbit            | Disk 01                                           | Disk 02                                            | SAME
                Tolkien/The Hobbit            | CD 1                                              | CD 2                                               | SAME
                Tolkien/The Hobbit            | The Hobbit.epub                                   | Disc 1                                             | SAME
                ""                            | CD1                                               | CD2                                                | DIFF
                Tolkien                       | The Hobbit - Part One.m4b                         | The Hobbit - Part Two.m4b                          | SAME
                Tolkien                       | The Hobbit (Part I).m4b                           | The Hobbit (Part II).m4b                           | SAME
                Tolkien                       | The Hobbit [1-2].m4b                              | The Hobbit [2-2].m4b                               | SAME
                Tolkien                       | The Hobbit (1 of 2).mp3                           | The Hobbit (2 of 2).mp3                            | SAME
                Tolkien                       | The Hobbit - Part One.epub                        | The Hobbit - Part Two.epub                         | DIFF
                Misc                          | The Hobbit.epub                                   | The Hobit.epub                                     | DIFF
                """)
        void decidesAPairTheSameWayInEitherOrder(String folder, String a, String b, String expected) {
            int books = expected.equals("SAME") ? 1 : 2;

            assertThat(partition(folder, a, b)).hasSize(books);
            assertThat(partition(folder, b, a)).hasSize(books);
        }
    }
}
