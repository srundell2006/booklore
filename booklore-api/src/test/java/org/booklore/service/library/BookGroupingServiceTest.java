package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookAuthorRow;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.repository.projection.FilelessBookRow;
import org.booklore.service.library.matching.ExistingBookMatcher.Ambiguous;
import org.booklore.service.library.matching.ExistingBookMatcher.Decision;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.booklore.service.library.matching.ExistingBookMatcher.Match;
import org.booklore.service.library.matching.ExistingBookMatcher.NoMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookGroupingServiceTest {

    @Mock
    BookRepository bookRepository;

    BookGroupingService bookGroupingService;

    @BeforeEach
    void setUp() {
        bookGroupingService = new BookGroupingService(bookRepository, new FilelessShells(bookRepository));
    }

    private LibraryPathEntity pathEntity(Long id, String path) {
        var pe = new LibraryPathEntity();
        pe.setId(id);
        pe.setPath(path);
        return pe;
    }

    private LibraryEntity library(LibraryOrganizationMode mode, LibraryPathEntity... paths) {
        return LibraryEntity.builder()
                .name("Test Library")
                .organizationMode(mode)
                .libraryPaths(List.of(paths))
                .build();
    }

    private LibraryFile file(LibraryPathEntity path, String subPath, String name, BookFileType type) {
        return LibraryFile.builder()
                .libraryPathEntity(path)
                .fileSubPath(subPath)
                .fileName(name)
                .bookFileType(type)
                .build();
    }

    private LibraryFile file(LibraryPathEntity path, String subPath, String name, BookFileType type, boolean folderBased) {
        return LibraryFile.builder()
                .libraryPathEntity(path)
                .fileSubPath(subPath)
                .fileName(name)
                .bookFileType(type)
                .folderBased(folderBased)
                .build();
    }

    private LibraryFile ebook(LibraryPathEntity path, String subPath, String name) {
        return file(path, subPath, name, BookFileType.EPUB);
    }

    private LibraryFile audiobook(LibraryPathEntity path, String subPath, String name) {
        return file(path, subPath, name, BookFileType.AUDIOBOOK);
    }

    private LibraryFile folderAudiobook(LibraryPathEntity path, String subPath, String folderName) {
        return file(path, subPath, folderName, BookFileType.AUDIOBOOK, true);
    }

    private int groupCount(Map<String, List<LibraryFile>> groups) {
        return groups.size();
    }

    private int totalFiles(Map<String, List<LibraryFile>> groups) {
        return groups.values().stream().mapToInt(List::size).sum();
    }

    private int folderBasedCount(Map<String, List<LibraryFile>> groups) {
        return (int) groups.values().stream()
                .flatMap(Collection::stream)
                .filter(LibraryFile::isFolderBased)
                .count();
    }

    @Test
    void structure1_bookPerFile_flatRootFiles() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                ebook(path, "", "Salem's Lot (Package May Vary) - Stephen Kingg, Aditya.epub"),
                ebook(path, "", "Good Spirits (B.K. Borison) (z-library.sk, 1lib.sk, z-lib.sk).epub"),
                ebook(path, "", "Hansel and Gretel - Stephen King (2025).epub"),
                audiobook(path, "", "Hansel and Gretel (2025).m4b"),
                ebook(path, "", "History of Britain and Ireland The Definitive Visual Guide (DORLING KINDERSLEY.) (Z-Library).epub"),
                ebook(path, "", "The Strength of the Few (Hierarchy, Book Two) (James Islington) (z-library.sk, 1lib.sk, z-lib.sk).epub"),
                ebook(path, "", "Violet Thistlewaite Is Not a Villain Anymore (Emily Krempholtz) (z-library.sk, 1lib.sk, z-lib.sk).epub")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(7);
        assertThat(totalFiles(groups)).isEqualTo(7);
        assertThat(folderBasedCount(groups)).isZero();
    }

    @Test
    void structure1_bookPerFolder_flatRootFiles() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "", "Salem's Lot (Package May Vary) - Stephen Kingg, Aditya.epub"),
                ebook(path, "", "Good Spirits (B.K. Borison) (z-library.sk, 1lib.sk, z-lib.sk).epub"),
                ebook(path, "", "Hansel and Gretel - Stephen King (2025).epub"),
                audiobook(path, "", "Hansel and Gretel (2025).m4b"),
                ebook(path, "", "History of Britain and Ireland The Definitive Visual Guide (DORLING KINDERSLEY.) (Z-Library).epub"),
                ebook(path, "", "The Strength of the Few (Hierarchy, Book Two) (James Islington) (z-library.sk, 1lib.sk, z-lib.sk).epub"),
                ebook(path, "", "Violet Thistlewaite Is Not a Villain Anymore (Emily Krempholtz) (z-library.sk, 1lib.sk, z-lib.sk).epub")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(7);
        assertThat(totalFiles(groups)).isEqualTo(7);
    }

    @Test
    void structure2_bookPerFile_authorTitleFolders() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Andy Weir/Project Hail Mary", "Project Hail Mary - Andy Weir (2022).pdf"),
                ebook(path, "Andy Weir/The Martian", "The Martian - Andy Weir (2020).epub"),
                audiobook(path, "Andy Weir/The Martian", "The Martian - Andy Weir (2020).mp3"),
                ebook(path, "Christopher Buehlman/The Lesser Dead", "The Lesser Dead - Christopher Buehlman (2014).epub"),
                ebook(path, "Christopher Buehlman/Those Across the River", "Those Across the River - Christopher Buehlman (2011).epub"),
                ebook(path, "Cormac McCarthy/The Road", "The Road - Cormac McCarthy (2006).epub"),
                ebook(path, "Jim Butcher/Twelve Months", "Twelve Months - Jim Butcher (2026).azw3"),
                ebook(path, "Jim Butcher/Twelve Months", "Twelve Months - Jim Butcher (2026).epub"),
                ebook(path, "Jim Butcher/Twelve Months", "Twelve Months - Jim Butcher (2026).mobi"),
                ebook(path, "Neil Gaiman/American Gods", "American Gods - Neil Gaiman (2011).epub"),
                ebook(path, "Neil Gaiman/American Gods", "American Gods - Neil Gaiman (2011).mobi"),
                ebook(path, "James Islington/The Will of the Many", "The Will of the Many (Hierarchy) - James Islington (2025).epub")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(12);
        assertThat(totalFiles(groups)).isEqualTo(12);
        assertThat(folderBasedCount(groups)).isZero();
    }

    @Test
    void structure2_bookPerFolder_authorTitleFolders() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Andy Weir/Project Hail Mary", "Project Hail Mary - Andy Weir (2022).pdf"),
                ebook(path, "Andy Weir/The Martian", "The Martian - Andy Weir (2020).epub"),
                audiobook(path, "Andy Weir/The Martian", "The Martian - Andy Weir (2020).mp3"),
                ebook(path, "Christopher Buehlman/The Lesser Dead", "The Lesser Dead - Christopher Buehlman (2014).epub"),
                ebook(path, "Christopher Buehlman/Those Across the River", "Those Across the River - Christopher Buehlman (2011).epub"),
                ebook(path, "Cormac McCarthy/The Road", "The Road - Cormac McCarthy (2006).epub"),
                ebook(path, "Jim Butcher/Twelve Months", "Twelve Months - Jim Butcher (2026).azw3"),
                ebook(path, "Jim Butcher/Twelve Months", "Twelve Months - Jim Butcher (2026).epub"),
                ebook(path, "Jim Butcher/Twelve Months", "Twelve Months - Jim Butcher (2026).mobi"),
                ebook(path, "Neil Gaiman/American Gods", "American Gods - Neil Gaiman (2011).epub"),
                ebook(path, "Neil Gaiman/American Gods", "American Gods - Neil Gaiman (2011).mobi"),
                ebook(path, "James Islington/The Will of the Many", "The Will of the Many (Hierarchy) - James Islington (2025).epub")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(8);
        assertThat(totalFiles(groups)).isEqualTo(12);
    }

    @Test
    void structure2_bookPerFolder_twelveMonthsGroupedAsSingleBook() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Jim Butcher/Twelve Months", "Twelve Months - Jim Butcher (2026).azw3"),
                ebook(path, "Jim Butcher/Twelve Months", "Twelve Months - Jim Butcher (2026).epub"),
                ebook(path, "Jim Butcher/Twelve Months", "Twelve Months - Jim Butcher (2026).mobi")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(1);
        assertThat(totalFiles(groups)).isEqualTo(3);
    }

    @Test
    void structure3_bookPerFile_folderBasedAudiobooks() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "1 HARRY POTTER AND THE PHILOSOPHER'S STONE"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "2 HARRY POTTER AND THE CHAMBER OF SECRETS"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "3 HARRY POTTER AND THE PRISONER OF AZKABAN")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(3);
        assertThat(folderBasedCount(groups)).isEqualTo(3);
    }

    @Test
    void structure3_bookPerFolder_folderBasedAudiobooksGroupedUnderParent() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "1 HARRY POTTER AND THE PHILOSOPHER'S STONE"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "2 HARRY POTTER AND THE CHAMBER OF SECRETS"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "3 HARRY POTTER AND THE PRISONER OF AZKABAN")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        // Folder-based audiobooks reconstruct effectiveSubPath = subPath/fileName, giving each a
        // unique folder path. With no ebook ancestor to absorb into, each stays separate.
        assertThat(groupCount(groups)).isEqualTo(3);
        assertThat(totalFiles(groups)).isEqualTo(3);
        assertThat(folderBasedCount(groups)).isEqualTo(3);
    }

    @Test
    void structure4_bookPerFile_mixedMedia() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Suzanne Collins/Catching Fire", "Catching Fire - Suzanne Collins.azw3"),
                ebook(path, "Suzanne Collins/Catching Fire", "Catching Fire - Suzanne Collins.epub"),
                folderAudiobook(path, "Suzanne Collins/Catching Fire/audiobook", "audiobook"),
                ebook(path, "Suzanne Collins/The Hunger Games", "The Hunger Games - Suzanne Collins (2010).azw3"),
                ebook(path, "Suzanne Collins/The Hunger Games", "The Hunger Games - Suzanne Collins (2010).epub"),
                ebook(path, "Suzanne Collins/The Hunger Games", "The Hunger Games - Suzanne Collins (2010).mobi")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(6);
        assertThat(totalFiles(groups)).isEqualTo(6);
        assertThat(folderBasedCount(groups)).isEqualTo(1);
    }

    @Test
    void structure4_bookPerFolder_audiobookAbsorbedIntoEbookFolder() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Suzanne Collins/Catching Fire", "Catching Fire - Suzanne Collins.azw3"),
                ebook(path, "Suzanne Collins/Catching Fire", "Catching Fire - Suzanne Collins.epub"),
                folderAudiobook(path, "Suzanne Collins/Catching Fire/audiobook", "audiobook"),
                ebook(path, "Suzanne Collins/The Hunger Games", "The Hunger Games - Suzanne Collins (2010).azw3"),
                ebook(path, "Suzanne Collins/The Hunger Games", "The Hunger Games - Suzanne Collins (2010).epub"),
                ebook(path, "Suzanne Collins/The Hunger Games", "The Hunger Games - Suzanne Collins (2010).mobi")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(2);
        assertThat(totalFiles(groups)).isEqualTo(6);
        assertThat(folderBasedCount(groups)).isEqualTo(1);

        var catchingFireGroup = groups.values().stream()
                .filter(g -> g.stream().anyMatch(f -> f.getFileName().contains("Catching Fire")))
                .findFirst().orElseThrow();
        assertThat(catchingFireGroup).hasSize(3);
    }

    @Test
    void structure5_bookPerFile_formatSubfolders() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - EPUB", "Brandon Sanderson - Mistborn 01 - The Final Empire.epub"),
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - EPUB", "Brandon Sanderson - Mistborn 02 - The Well of Ascension.epub"),
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - EPUB", "Brandon Sanderson - Mistborn 03 - The Hero of Ages.epub"),
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - MOBI", "Brandon Sanderson - Mistborn 01 - The Final Empire.mobi"),
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - MOBI", "Brandon Sanderson - Mistborn 02 - The Well of Ascension.mobi"),
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - MOBI", "Brandon Sanderson - Mistborn 03 - The Hero of Ages.mobi")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(6);
        assertThat(totalFiles(groups)).isEqualTo(6);
    }

    @Test
    void structure5_bookPerFolder_formatSubfolders() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - EPUB", "Brandon Sanderson - Mistborn 01 - The Final Empire.epub"),
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - EPUB", "Brandon Sanderson - Mistborn 02 - The Well of Ascension.epub"),
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - EPUB", "Brandon Sanderson - Mistborn 03 - The Hero of Ages.epub"),
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - MOBI", "Brandon Sanderson - Mistborn 01 - The Final Empire.mobi"),
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - MOBI", "Brandon Sanderson - Mistborn 02 - The Well of Ascension.mobi"),
                ebook(path, "Brandon Sanderson - Mistborn Trilogy/Brandon Sanderson - Mistborn - MOBI", "Brandon Sanderson - Mistborn 03 - The Hero of Ages.mobi")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(2);
        assertThat(totalFiles(groups)).isEqualTo(6);
    }

    @Test
    void structure6_bookPerFile_singleFileAudiobooksWithEbooks() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                ebook(path, "J. M. Barrie/Peter Pan", "Peter Pan - J. M. Barrie (2013).epub"),
                audiobook(path, "J. M. Barrie/Peter Pan", "Peter Pan - J. M. Barrie (2013).m4b"),
                ebook(path, "J. M. Barrie/Peter Pan", "Peter Pan - J. M. Barrie (2013).mobi"),
                ebook(path, "Jonathan Swift/Gulliver's Travels", "Gulliver's Travels - Jonathan Swift (2013).epub"),
                audiobook(path, "Jonathan Swift/Gulliver's Travels", "Gulliver's Travels - Jonathan Swift (2013).m4b"),
                ebook(path, "Jonathan Swift/Gulliver's Travels", "Gulliver's Travels - Jonathan Swift (2013).mobi"),
                ebook(path, "", "Hansel and Gretel - Stephen King (2025).epub"),
                audiobook(path, "", "Hansel and Gretel (2025).m4b")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(8);
        assertThat(totalFiles(groups)).isEqualTo(8);
    }

    @Test
    void structure6_bookPerFolder_singleFileAudiobooksGroupedWithEbooks() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "J. M. Barrie/Peter Pan", "Peter Pan - J. M. Barrie (2013).epub"),
                audiobook(path, "J. M. Barrie/Peter Pan", "Peter Pan - J. M. Barrie (2013).m4b"),
                ebook(path, "J. M. Barrie/Peter Pan", "Peter Pan - J. M. Barrie (2013).mobi"),
                ebook(path, "Jonathan Swift/Gulliver's Travels", "Gulliver's Travels - Jonathan Swift (2013).epub"),
                audiobook(path, "Jonathan Swift/Gulliver's Travels", "Gulliver's Travels - Jonathan Swift (2013).m4b"),
                ebook(path, "Jonathan Swift/Gulliver's Travels", "Gulliver's Travels - Jonathan Swift (2013).mobi"),
                ebook(path, "", "Hansel and Gretel - Stephen King (2025).epub"),
                audiobook(path, "", "Hansel and Gretel (2025).m4b")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(4);
        assertThat(totalFiles(groups)).isEqualTo(8);
    }

    @Test
    void structure7_bookPerFile_titleFolders() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Oathbringer_ The Stormlight Archive Book Three", "Oathbringer_ The Stormlight Archive Book T - Brandon Sanderson.azw3"),
                ebook(path, "Oathbringer_ The Stormlight Archive Book Three", "Oathbringer_ The Stormlight Archive Book T - Brandon Sanderson.epub"),
                ebook(path, "Oathbringer_ The Stormlight Archive Book Three", "Oathbringer_ The Stormlight Archive Book T - Brandon Sanderson.mobi"),
                ebook(path, "Piranesi by Susanna Clarke", "Piranesi - Susanna Clarke.epub"),
                ebook(path, "Piranesi by Susanna Clarke", "Piranesi - Susanna Clarke.mobi"),
                ebook(path, "Rhythm of War (Stormlight Archive Book 4) - Brandon Sanderson", "Rhythm of War (The Stormlight Archive Book 4) - Brandon Sanderson.azw3"),
                ebook(path, "Rhythm of War (Stormlight Archive Book 4) - Brandon Sanderson", "Rhythm of War (The Stormlight Archive Book 4) - Brandon Sanderson.epub"),
                ebook(path, "Rhythm of War (Stormlight Archive Book 4) - Brandon Sanderson", "Rhythm of War (The Stormlight Archive Book 4) - Brandon Sanderson.mobi"),
                ebook(path, "The Martian_ A Novel (283)", "The Martian_ A Novel - Andy Weir.azw3"),
                ebook(path, "The Martian_ A Novel (283)", "The Martian_ A Novel - Andy Weir.epub"),
                ebook(path, "The Martian_ A Novel (283)", "The Martian_ A Novel - Andy Weir.mobi")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(11);
        assertThat(totalFiles(groups)).isEqualTo(11);
    }

    @Test
    void structure7_bookPerFolder_titleFolders() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Oathbringer_ The Stormlight Archive Book Three", "Oathbringer_ The Stormlight Archive Book T - Brandon Sanderson.azw3"),
                ebook(path, "Oathbringer_ The Stormlight Archive Book Three", "Oathbringer_ The Stormlight Archive Book T - Brandon Sanderson.epub"),
                ebook(path, "Oathbringer_ The Stormlight Archive Book Three", "Oathbringer_ The Stormlight Archive Book T - Brandon Sanderson.mobi"),
                ebook(path, "Piranesi by Susanna Clarke", "Piranesi - Susanna Clarke.epub"),
                ebook(path, "Piranesi by Susanna Clarke", "Piranesi - Susanna Clarke.mobi"),
                ebook(path, "Rhythm of War (Stormlight Archive Book 4) - Brandon Sanderson", "Rhythm of War (The Stormlight Archive Book 4) - Brandon Sanderson.azw3"),
                ebook(path, "Rhythm of War (Stormlight Archive Book 4) - Brandon Sanderson", "Rhythm of War (The Stormlight Archive Book 4) - Brandon Sanderson.epub"),
                ebook(path, "Rhythm of War (Stormlight Archive Book 4) - Brandon Sanderson", "Rhythm of War (The Stormlight Archive Book 4) - Brandon Sanderson.mobi"),
                ebook(path, "The Martian_ A Novel (283)", "The Martian_ A Novel - Andy Weir.azw3"),
                ebook(path, "The Martian_ A Novel (283)", "The Martian_ A Novel - Andy Weir.epub"),
                ebook(path, "The Martian_ A Novel (283)", "The Martian_ A Novel - Andy Weir.mobi")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(4);
        assertThat(totalFiles(groups)).isEqualTo(11);
    }

    @Test
    void structure8_bookPerFile_deepNesting() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/Prince Caspian", "Prince Caspian - C. S. Lewis (2013).azw3"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/Prince Caspian", "Prince Caspian - C. S. Lewis (2013).epub"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/Prince Caspian", "Prince Caspian - C. S. Lewis (2013).mobi"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Horse and His Boy The Chronicles of Narnia", "The Horse and His Boy - C. S. Lewis.azw3"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Horse and His Boy The Chronicles of Narnia", "The Horse and His Boy - C. S. Lewis.epub"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Horse and His Boy The Chronicles of Narnia", "The Horse and His Boy - C. S. Lewis.mobi"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Magician's Nephew The Chronicles of Narnia", "The Magician's Nephew - C. S. Lewis (2013).epub"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Magician's Nephew The Chronicles of Narnia", "The Magician's Nephew - C. S. Lewis.azw3"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Magician's Nephew The Chronicles of Narnia", "The Magician's Nephew - C. S. Lewis.mobi"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Silver Chair", "The Silver Chair - C. S. Lewis (2013).epub")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(10);
        assertThat(totalFiles(groups)).isEqualTo(10);
    }

    @Test
    void structure8_bookPerFolder_deepNesting() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/Prince Caspian", "Prince Caspian - C. S. Lewis (2013).azw3"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/Prince Caspian", "Prince Caspian - C. S. Lewis (2013).epub"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/Prince Caspian", "Prince Caspian - C. S. Lewis (2013).mobi"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Horse and His Boy The Chronicles of Narnia", "The Horse and His Boy - C. S. Lewis.azw3"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Horse and His Boy The Chronicles of Narnia", "The Horse and His Boy - C. S. Lewis.epub"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Horse and His Boy The Chronicles of Narnia", "The Horse and His Boy - C. S. Lewis.mobi"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Magician's Nephew The Chronicles of Narnia", "The Magician's Nephew - C. S. Lewis (2013).epub"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Magician's Nephew The Chronicles of Narnia", "The Magician's Nephew - C. S. Lewis.azw3"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Magician's Nephew The Chronicles of Narnia", "The Magician's Nephew - C. S. Lewis.mobi"),
                ebook(path, "C. S. Lewis/The Chronicles of Narnia/The Silver Chair", "The Silver Chair - C. S. Lewis (2013).epub")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(4);
        assertThat(totalFiles(groups)).isEqualTo(10);
    }

    @Test
    void structure9_bookPerFile_largeSeries() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = new ArrayList<>();
        for (int i = 1; i <= 19; i++) {
            String name = String.format("Dune %02d - Title %d", i, i);
            files.add(ebook(path, "Dune/epub", name + ".epub"));
            files.add(ebook(path, "Dune/Mobi", name + ".mobi"));
        }

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(38);
        assertThat(totalFiles(groups)).isEqualTo(38);
    }

    @Test
    void structure9_bookPerFolder_largeSeries() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = new ArrayList<>();
        for (int i = 1; i <= 19; i++) {
            String name = String.format("Dune %02d - Title %d", i, i);
            files.add(ebook(path, "Dune/epub", name + ".epub"));
            files.add(ebook(path, "Dune/Mobi", name + ".mobi"));
        }

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(2);
        assertThat(totalFiles(groups)).isEqualTo(38);
    }

    @Test
    void structure10_bookPerFile_hpEbooks() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                ebook(path, "J.K. Rowling/Harry Potter and the Chamber of Secrets", "Harry Potter and the Chamber of Secrets - J.K. Rowling (2019).fb2"),
                ebook(path, "J.K. Rowling/Harry Potter and the Chamber of Secrets", "Harry Potter and the Chamber of Secrets - J.K. Rowling (2019).pdf"),
                ebook(path, "J.K. Rowling/Harry Potter and the Chamber of Secrets", "Harry Potter and the Chamber of Secrets - J.K. Rowling.azw3"),
                ebook(path, "J.K. Rowling/Harry Potter and the Chamber of Secrets", "Harry Potter and the Chamber of Secrets - J.K. Rowling.epub"),
                ebook(path, "J.K. Rowling/Harry Potter and the Cursed Child", "Harry Potter and the Cursed Child - J.K. Rowling (2017).epub"),
                ebook(path, "J.K. Rowling/Harry Potter and the Deathly Hallows", "Harry Potter and the Deathly Hallows - J.K. Rowling.azw3"),
                ebook(path, "J.K. Rowling/Harry Potter and the Deathly Hallows", "Harry Potter and the Deathly Hallows - J.K. Rowling.epub"),
                ebook(path, "J.K. Rowling/Harry Potter and the Goblet of Fire", "Harry Potter and the Goblet of Fire - J.K. Rowling.epub"),
                ebook(path, "J.K. Rowling/Harry Potter and the Goblet of Fire", "Harry Potter and the Goblet of Fire - J.K. Rowling.mobi"),
                ebook(path, "J.K. Rowling/Harry Potter and the Goblet of Fire", "Harry Potter and the Goblet of Fire - J.K. Rowling.pdf"),
                ebook(path, "J.K. Rowling/Harry Potter and the Half-Blood Prince", "Harry Potter and the Half-Blood Prince - J.K. Rowling.epub"),
                ebook(path, "J.K. Rowling/Harry Potter and the Philosopher's Stone", "Harry Potter and the Philosopher's Stone - J.K. Rowling.azw3"),
                ebook(path, "J.K. Rowling/Harry Potter and the Philosopher's Stone", "Harry Potter and the Philosopher's Stone - J.K. Rowling.epub")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(13);
        assertThat(totalFiles(groups)).isEqualTo(13);
    }

    @Test
    void structure10_bookPerFolder_hpEbooks() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "J.K. Rowling/Harry Potter and the Chamber of Secrets", "Harry Potter and the Chamber of Secrets - J.K. Rowling (2019).fb2"),
                ebook(path, "J.K. Rowling/Harry Potter and the Chamber of Secrets", "Harry Potter and the Chamber of Secrets - J.K. Rowling (2019).pdf"),
                ebook(path, "J.K. Rowling/Harry Potter and the Chamber of Secrets", "Harry Potter and the Chamber of Secrets - J.K. Rowling.azw3"),
                ebook(path, "J.K. Rowling/Harry Potter and the Chamber of Secrets", "Harry Potter and the Chamber of Secrets - J.K. Rowling.epub"),
                ebook(path, "J.K. Rowling/Harry Potter and the Cursed Child", "Harry Potter and the Cursed Child - J.K. Rowling (2017).epub"),
                ebook(path, "J.K. Rowling/Harry Potter and the Deathly Hallows", "Harry Potter and the Deathly Hallows - J.K. Rowling.azw3"),
                ebook(path, "J.K. Rowling/Harry Potter and the Deathly Hallows", "Harry Potter and the Deathly Hallows - J.K. Rowling.epub"),
                ebook(path, "J.K. Rowling/Harry Potter and the Goblet of Fire", "Harry Potter and the Goblet of Fire - J.K. Rowling.epub"),
                ebook(path, "J.K. Rowling/Harry Potter and the Goblet of Fire", "Harry Potter and the Goblet of Fire - J.K. Rowling.mobi"),
                ebook(path, "J.K. Rowling/Harry Potter and the Goblet of Fire", "Harry Potter and the Goblet of Fire - J.K. Rowling.pdf"),
                ebook(path, "J.K. Rowling/Harry Potter and the Half-Blood Prince", "Harry Potter and the Half-Blood Prince - J.K. Rowling.epub"),
                ebook(path, "J.K. Rowling/Harry Potter and the Philosopher's Stone", "Harry Potter and the Philosopher's Stone - J.K. Rowling.azw3"),
                ebook(path, "J.K. Rowling/Harry Potter and the Philosopher's Stone", "Harry Potter and the Philosopher's Stone - J.K. Rowling.epub")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(6);
        assertThat(totalFiles(groups)).isEqualTo(13);
    }

    @Test
    void structure12_bookPerFile_fullAudiobooks() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "1 HARRY POTTER AND THE PHILOSOPHER'S STONE"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "2 HARRY POTTER AND THE CHAMBER OF SECRETS"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "3 HARRY POTTER AND THE PRISONER OF AZKABAN"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "4 HARRY POTTER AND THE GOBLET OF FIRE"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "5 HARRY POTTER ANND THE ORDER OF THE PHOENIX"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "6 HARRY POTTER AND THE HALF-BLOOD PRINCE"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "7 HARRY POTTER AND THE DEATHLY HALLOWS")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(7);
        assertThat(folderBasedCount(groups)).isEqualTo(7);
    }

    @Test
    void structure12_bookPerFolder_fullAudiobooks() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "1 HARRY POTTER AND THE PHILOSOPHER'S STONE"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "2 HARRY POTTER AND THE CHAMBER OF SECRETS"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "3 HARRY POTTER AND THE PRISONER OF AZKABAN"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "4 HARRY POTTER AND THE GOBLET OF FIRE"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "5 HARRY POTTER ANND THE ORDER OF THE PHOENIX"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "6 HARRY POTTER AND THE HALF-BLOOD PRINCE"),
                folderAudiobook(path, "J K Rowling - Harry Potter 1-7 Unabridged Audiobooks Narrated by Stephen Fry", "7 HARRY POTTER AND THE DEATHLY HALLOWS")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        // Each folder-based audiobook reconstructs effectiveSubPath = parentSubPath/folderName,
        // so with no ebook ancestor to absorb into, each stays separate
        assertThat(groupCount(groups)).isEqualTo(7);
        assertThat(totalFiles(groups)).isEqualTo(7);
        assertThat(folderBasedCount(groups)).isEqualTo(7);
    }

    @Test
    void structure13_bookPerFile_mismatchedNames() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Wendy Walker/Bladesssss", "Blade - Wendy Walddker.epub"),
                ebook(path, "Wendy Walker/Bladesssss", "Blade - Wendy Walker (2026).pdf")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(2);
    }

    @Test
    void structure13_bookPerFolder_mismatchedNames() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Wendy Walker/Bladesssss", "Blade - Wendy Walddker.epub"),
                ebook(path, "Wendy Walker/Bladesssss", "Blade - Wendy Walker (2026).pdf")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(1);
        assertThat(totalFiles(groups)).isEqualTo(2);
    }

    @Test
    void nullOrganizationMode_defaultsToAutoDetect() {
        var path = pathEntity(1L, "/books");
        var lib = library(null, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Author/Book", "Book - Author.epub"),
                ebook(path, "Author/Book", "Book - Author.mobi")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(totalFiles(groups)).isEqualTo(2);
    }

    @Test
    void bookPerFolder_rootLevelAudiobooksAreIndividual() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                audiobook(path, "", "Audiobook One.m4b"),
                audiobook(path, "", "Audiobook Two.m4b"),
                ebook(path, "", "Some Book.epub")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(3);
    }

    @Test
    void bookPerFolder_audiobookAbsorbedIntoNearestEbookAncestor() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Author/Title", "Title - Author.epub"),
                folderAudiobook(path, "Author/Title/audiobook", "audiobook")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(1);
        assertThat(totalFiles(groups)).isEqualTo(2);
    }

    @Test
    void bookPerFolder_audiobookWithNoEbookAncestorStaysSeparate() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path);

        List<LibraryFile> files = List.of(
                folderAudiobook(path, "Audiobooks/Book One", "Book One"),
                ebook(path, "Ebooks/Some Book", "Some Book.epub")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(2);
    }

    @Test
    void bookPerFile_sameNameDifferentFormatsAreSeparateBooks() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FILE, path);

        List<LibraryFile> files = List.of(
                ebook(path, "Author/Book", "Book.epub"),
                ebook(path, "Author/Book", "Book.mobi"),
                ebook(path, "Author/Book", "Book.pdf")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(3);
    }

    @Test
    void bookPerFolder_differentLibraryPathsAreNotMixed() {
        var path1 = pathEntity(1L, "/books1");
        var path2 = pathEntity(2L, "/books2");
        var lib = library(LibraryOrganizationMode.BOOK_PER_FOLDER, path1, path2);

        List<LibraryFile> files = List.of(
                ebook(path1, "Author/Title", "Book.epub"),
                ebook(path1, "Author/Title", "Book.mobi"),
                ebook(path2, "Author/Title", "Book.epub"),
                ebook(path2, "Author/Title", "Book.mobi")
        );

        var groups = bookGroupingService.groupForInitialScan(files, lib);

        assertThat(groupCount(groups)).isEqualTo(2);
        assertThat(totalFiles(groups)).isEqualTo(4);
    }

    // ---- rescan grouping: decided from the scan rows, the library's fileless books and their authors ----

    private final List<BookFileScanRow> rows = new ArrayList<>();
    private final List<FilelessBookRow> shellRows = new ArrayList<>();
    private final List<BookAuthorRow> authorRows = new ArrayList<>();

    private LibraryEntity rescanLibrary(LibraryOrganizationMode mode, LibraryPathEntity path) {
        LibraryEntity lib = library(mode, path);
        lib.setId(1L);
        return lib;
    }

    private LibraryFile newFile(LibraryEntity lib, LibraryPathEntity path, String subPath, String name) {
        return LibraryFile.builder()
                .libraryEntity(lib)
                .libraryPathEntity(path)
                .fileSubPath(subPath)
                .fileName(name)
                .bookFileType(BookFileExtension.fromFileName(name).orElseThrow().getType())
                .build();
    }

    /** A book with these book files in one folder, as the rescan's scan rows have it. */
    private void bookIn(long id, LibraryPathEntity path, String subPath, String... names) {
        long fileId = id * 10;
        for (String name : names) {
            rows.add(new BookFileScanRow(fileId++, id, path.getId(), path.getPath(), subPath, name, true,
                    BookFileExtension.fromFileName(name).orElseThrow().getType(), false));
        }
    }

    private void authorsOf(long bookId, String... names) {
        for (String name : names) {
            authorRows.add(new BookAuthorRow(bookId, name));
        }
    }

    private void shell(long id, String title, String... authors) {
        shell(id, false, title, authors);
    }

    private void shell(long id, boolean physical, String title, String... authors) {
        if (authors.length == 0) {
            shellRows.add(new FilelessBookRow(id, null, physical, "cover", null, title, null, null, null, null));
        }
        for (String author : authors) {
            shellRows.add(new FilelessBookRow(id, null, physical, "cover", null, title, null, null, null, author));
        }
    }

    private BookGroupingService.GroupingResult rescan(LibraryEntity lib, LibraryFile... files) {
        return rescan(lib, BookGroupingService.DecisionListener.NONE, files);
    }

    private BookGroupingService.GroupingResult rescan(LibraryEntity lib, BookGroupingService.DecisionListener listener,
                                                      LibraryFile... files) {
        when(bookRepository.findFilelessBookRowsByLibraryId(1L)).thenReturn(shellRows);
        lenient().when(bookRepository.findAuthorNamesByBookIds(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return authorRows.stream().filter(row -> ids.contains(row.bookId())).toList();
        });
        return bookGroupingService.groupForRescan(List.of(files), lib, new LibraryBookFiles(rows), listener);
    }

    private static Map<Long, List<LibraryFile>> attached(BookGroupingService.GroupingResult result) {
        Map<Long, List<LibraryFile>> byBook = new LinkedHashMap<>();
        result.filesToAttach().forEach(a -> byBook.put(a.bookId(), a.files()));
        return byBook;
    }

    private static Map<Long, Kind> kinds(BookGroupingService.GroupingResult result) {
        Map<Long, Kind> byBook = new LinkedHashMap<>();
        result.filesToAttach().forEach(a -> byBook.put(a.bookId(), a.kind()));
        return byBook;
    }

    private static List<List<String>> newBooks(BookGroupingService.GroupingResult result) {
        return result.newBookGroups().values().stream()
                .map(group -> group.stream().map(LibraryFile::getFileName).toList())
                .toList();
    }

    /** Only the one fileless query, and the batched author query when a folder of a new file has books. */
    private void verifyNoQueryPerFolderOrFile(int authorQueries) {
        verify(bookRepository, times(1)).findFilelessBookRowsByLibraryId(1L);
        verify(bookRepository, times(authorQueries)).findAuthorNamesByBookIds(anyCollection());
        verifyNoMoreInteractions(bookRepository);
    }

    @Test
    void rescan_isNotATransaction() throws NoSuchMethodException {
        // Grouping a rescan's new files held one read-only transaction open for an hour and a half in production
        var method = BookGroupingService.class.getMethod("groupForRescan", List.class, LibraryEntity.class, LibraryBookFiles.class);
        assertThat(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(method.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        assertThat(BookGroupingService.class.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }

    @Test
    void rescan_bookPerFolder_attachesToTheBookOfTheFolderFromTheScanRows() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        bookIn(100, path, "Author/Title", "Title.epub");
        LibraryFile mobi = newFile(lib, path, "Author/Title", "Title.mobi");
        LibraryFile pdf = newFile(lib, path, "Author/Title", "Title.pdf");
        LibraryFile other = newFile(lib, path, "Author/Other", "Other.epub");

        var result = rescan(lib, mobi, pdf, other);

        assertThat(attached(result)).containsExactly(Map.entry(100L, List.of(mobi, pdf)));
        assertThat(kinds(result)).containsExactly(Map.entry(100L, Kind.SAME_FOLDER));
        assertThat(result.newBookGroups()).containsOnlyKeys("1:Author/Other");
        assertThat(result.newBookGroups().get("1:Author/Other")).containsExactly(other);
        verifyNoQueryPerFolderOrFile(0);
    }

    @Test
    void rescan_bookPerFolder_audiobookIsAbsorbedByTheBookWithAnEbookAbove() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        bookIn(200, path, "Author/Book", "Book.epub");
        LibraryFile ch1 = newFile(lib, path, "Author/Book/audio", "ch1.mp3");
        LibraryFile ch2 = newFile(lib, path, "Author/Book/audio", "ch2.mp3");
        LibraryFile ch3 = newFile(lib, path, "Author/Book/audio/disc2", "ch3.mp3");

        var result = rescan(lib, ch1, ch2, ch3);

        assertThat(attached(result)).containsExactly(Map.entry(200L, List.of(ch1, ch2, ch3)));
        assertThat(result.newBookGroups()).isEmpty();
        // Before, each file queried its own directory and then every ancestor again
        verifyNoQueryPerFolderOrFile(0);
    }

    @Test
    void rescan_bookPerFolder_aFolderWithSeveralBooksAttachesToNone() {
        // Before, the book with the most files took the new file: a guess
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        bookIn(300, path, "Author/Mixed", "A.epub", "A.pdf");
        bookIn(301, path, "Author/Mixed", "B.epub");
        LibraryFile c = newFile(lib, path, "Author/Mixed", "C.mobi");

        var result = rescan(lib, c);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(result.newBookGroups()).containsOnlyKeys("1:Author/Mixed");
    }

    @Test
    void rescan_autoDetect_matchesFilelessAndExistingBooksWithOneFilelessQuery() {
        // Changed: the fileless book had no author, which no longer matches anything (it is not physical); it now has
        // its author, and the files are in his folder
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(300, "The Martian", "Andy Weir");
        bookIn(400, path, "Andy Weir/Hail Mary", "Project Hail Mary.epub");
        LibraryFile martianEpub = newFile(lib, path, "Andy Weir/Martian", "The Martian.epub");
        LibraryFile martianAudio = newFile(lib, path, "Andy Weir/Martian", "The Martian.m4b");
        LibraryFile hailMaryPdf = newFile(lib, path, "Andy Weir/Hail Mary", "Project Hail Mary.pdf");
        LibraryFile unrelated = newFile(lib, path, "Andy Weir/Hail Mary", "Unrelated Thing.pdf");

        var result = rescan(lib, martianEpub, martianAudio, hailMaryPdf, unrelated);

        assertThat(attached(result)).containsExactly(
                Map.entry(300L, List.of(martianEpub, martianAudio)),
                Map.entry(400L, List.of(hailMaryPdf)));
        assertThat(kinds(result)).containsExactly(Map.entry(300L, Kind.FILELESS), Map.entry(400L, Kind.SAME_FOLDER));
        assertThat(newBooks(result)).containsExactly(List.of("Unrelated Thing.pdf"));
        verifyNoQueryPerFolderOrFile(1);
    }

    @Test
    void rescan_autoDetect_aFilelessBookWithoutAuthorsOnlyMatchesWhenPhysical() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(300, "The Martian");
        shell(301, true, "Dune");
        LibraryFile martian = newFile(lib, path, "Weir/Martian", "The Martian.epub");
        LibraryFile dune = newFile(lib, path, "Herbert", "Dune.epub");

        var result = rescan(lib, martian, dune);

        assertThat(attached(result)).containsExactly(Map.entry(301L, List.of(dune)));
        assertThat(newBooks(result)).containsExactly(List.of("The Martian.epub"));
    }

    @Test
    void rescan_bookPerFile_onlyMatchesFilelessBooksExactly() {
        // Changed: the fileless book has its author and the files are in his folder; without either it matches nothing
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FILE, path);
        shell(300, "Dune", "Frank Herbert");
        LibraryFile dune = newFile(lib, path, "Frank Herbert", "Dune.epub");
        LibraryFile duneMessiah = newFile(lib, path, "Frank Herbert", "Dune Messiah.epub");

        var result = rescan(lib, dune, duneMessiah);

        assertThat(attached(result)).containsExactly(Map.entry(300L, List.of(dune)));
        assertThat(result.newBookGroups()).containsOnlyKeys("1:Frank Herbert:Dune Messiah.epub");
        verifyNoQueryPerFolderOrFile(0);
    }

    @Test
    void rescan_bookPerFile_aFilelessBookClaimedByTwoFilesGetsNeither() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FILE, path);
        shell(300, "Dune", "Frank Herbert");
        LibraryFile epub = newFile(lib, path, "Frank Herbert", "Dune.epub");
        LibraryFile pdf = newFile(lib, path, "Frank Herbert", "Dune.pdf");

        var result = rescan(lib, epub, pdf);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Dune.epub"), List.of("Dune.pdf"));
    }

    @Test
    void rescan_autoDetect_assignmentNovelsOfTwoFoldersAreNotAnotherAuthorsAssignment() {
        // Production: all five went to "Assignment" by I. Christie
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(167862, "Assignment", "I. Christie");
        LibraryFile karachi = newFile(lib, path, "A/Aarons, Edward S", "Assignment - Karachi.epub");
        LibraryFile stellaMarni = newFile(lib, path, "A/Aarons, Edward S", "Assignment - Stella Marni.epub");
        LibraryFile cairo = newFile(lib, path, "A/Aarons, Edward S", "Assignment - The Cairo Dancers.epub");
        LibraryFile maltese = newFile(lib, path, "A/Aarons, Edward S/Sam Durell", "Assignment - Maltese Maiden.epub");
        LibraryFile treason = newFile(lib, path, "A/Aarons, Edward S/Sam Durell", "Assignment - Treason.epub");

        var result = rescan(lib, karachi, stellaMarni, cairo, maltese, treason);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactlyInAnyOrder(
                List.of("Assignment - Karachi.epub"), List.of("Assignment - Stella Marni.epub"),
                List.of("Assignment - The Cairo Dancers.epub"), List.of("Assignment - Maltese Maiden.epub"),
                List.of("Assignment - Treason.epub"));
    }

    @Test
    void rescan_autoDetect_aFilelessBookClaimedFromThreeFoldersGetsNone() {
        // Production: three "Survival.epub" of three author folders went to one book
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(500, "Survival", "Ann Smith");
        LibraryFile first = newFile(lib, path, "S/Smith, Ann", "Survival.epub");
        LibraryFile second = newFile(lib, path, "S/Smith, Ann/Series", "Survival.epub");
        LibraryFile third = newFile(lib, path, "S/Smith, Ann/Other", "Survival.epub");

        var result = rescan(lib, first, second, third);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(result.newBookGroups()).hasSize(3);
    }

    @Test
    void rescan_autoDetect_anotherVolumeInTheFolderIsANewBook() {
        // Production: "A Dangerous Universe 3" went to the book of "A Dangerous Universe 5.epub", and "Kokoro
        // Connect Volume 4" to "Volume -"
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        bookIn(600, path, "S/Stuart Slade", "A Dangerous Universe 5.epub");
        bookIn(601, path, "K/Kokoro Connect", "Kokoro Connect Volume - Nise Random.epub");
        LibraryFile three = newFile(lib, path, "S/Stuart Slade", "A Dangerous Universe 3.epub");
        LibraryFile four = newFile(lib, path, "K/Kokoro Connect", "Kokoro Connect Volume 4 - Michi Random.epub");
        LibraryFile fivePdf = newFile(lib, path, "S/Stuart Slade", "A Dangerous Universe 5.pdf");

        var result = rescan(lib, three, four, fivePdf);

        assertThat(attached(result)).containsExactly(Map.entry(600L, List.of(fivePdf)));
        assertThat(newBooks(result)).containsExactlyInAnyOrder(
                List.of("A Dangerous Universe 3.epub"), List.of("Kokoro Connect Volume 4 - Michi Random.epub"));
        verifyNoQueryPerFolderOrFile(1);
    }

    @Test
    void rescan_autoDetect_kingAndKingOfTheLasVegasBadBoysDoNotBothGoToKing() {
        // Production: both new files went to one fileless "King"
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(700, "King", "Mary Lane");
        LibraryFile inAuthorFolder = newFile(lib, path, "L/Lane, Mary", "King.epub");
        LibraryFile seriesInAuthorFolder = newFile(lib, path, "L/Lane, Mary", "KING - Las Vegas Bad Boys.epub");

        var result = rescan(lib, inAuthorFolder, seriesInAuthorFolder);

        assertThat(attached(result)).containsExactly(Map.entry(700L, List.of(inAuthorFolder)));
        assertThat(newBooks(result)).containsExactly(List.of("KING - Las Vegas Bad Boys.epub"));

        // In the series' own folder both read as "King": two different epubs, so neither
        LibraryFile inSeriesFolder = newFile(lib, path, "L/Lane, Mary/Las Vegas Bad Boys", "King.epub");
        LibraryFile seriesInSeriesFolder = newFile(lib, path, "L/Lane, Mary/Las Vegas Bad Boys", "KING - Las Vegas Bad Boys.epub");
        var inSeries = bookGroupingService.groupForRescan(List.of(inSeriesFolder, seriesInSeriesFolder), lib, new LibraryBookFiles(rows));
        assertThat(inSeries.filesToAttach()).isEmpty();
    }

    @Test
    void rescan_autoDetect_aSubtitledFileIsNotTheBookWithoutTheSubtitle() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        bookIn(800, path, "A/Archer, Jeffrey", "A Quiver Full of Arrows.epub");
        LibraryFile stories = newFile(lib, path, "A/Archer, Jeffrey", "A Quiver Full of Arrows - Stories.epub");

        var result = rescan(lib, stories);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("A Quiver Full of Arrows - Stories.epub"));
    }

    @Test
    void rescan_autoDetect_aCopyGoesWhereItsOriginalGoes() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        // Production: kept, as it should be
        bookIn(900, path, "A/Anthony, Michael", "Mass Casualties - Michael Anthony (2009).epub");
        LibraryFile copy = newFile(lib, path, "A/Anthony, Michael", "Mass Casualties - Michael Anthony (2009) (1).epub");
        // A lone " (1)" also reads as the copy it is; the two are one name for the claim rules
        shell(901, "The Martian", "Andy Weir");
        LibraryFile martian = newFile(lib, path, "W/Weir, Andy", "The Martian.epub");
        LibraryFile martianCopy = newFile(lib, path, "W/Weir, Andy", "The Martian (1).epub");

        var result = rescan(lib, copy, martian, martianCopy);

        assertThat(attached(result)).containsExactly(
                Map.entry(900L, List.of(copy)),
                Map.entry(901L, List.of(martian, martianCopy)));
        assertThat(result.newBookGroups()).isEmpty();
    }

    /** Each file's own decision against the fileless books, as the rescan tells its listener. */
    private BookGroupingService.DecisionListener filelessDecisions(Map<String, Decision> decisions) {
        return new BookGroupingService.DecisionListener() {
            @Override
            public void fileless(LibraryFile file, Decision decision) {
                decisions.put(file.getFileName(), decision);
            }
        };
    }

    @Test
    void rescan_autoDetect_aSecondDownloadMatchesTheFilelessBookOfItsTitleOnItsOwn() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(910, "Started Early, Took My Dog", "Kate Atkinson");
        shell(911, "The Martian", "Andy Weir");
        LibraryFile alone = newFile(lib, path, "A/Kate Atkinson", "Started Early, Took My Dog (1).epub");
        LibraryFile martian = newFile(lib, path, "W/Weir, Andy", "The Martian.epub");
        LibraryFile martianCopy = newFile(lib, path, "W/Weir, Andy", "The Martian (1).epub");
        Map<String, Decision> decisions = new LinkedHashMap<>();

        var result = rescan(lib, filelessDecisions(decisions), alone, martian, martianCopy);

        assertThat(attached(result)).containsExactly(
                Map.entry(910L, List.of(alone)),
                Map.entry(911L, List.of(martian, martianCopy)));
        assertThat(kinds(result)).containsOnly(Map.entry(910L, Kind.FILELESS), Map.entry(911L, Kind.FILELESS));
        assertThat(result.newBookGroups()).isEmpty();
        assertThat(decisions.get("Started Early, Took My Dog (1).epub").reason()).contains("read as a copy");
        assertThat(decisions.get("The Martian (1).epub")).isInstanceOf(Match.class);
        assertThat(decisions.get("The Martian (1).epub").reason()).contains("read as a copy");
    }

    @Test
    void rescan_autoDetect_aSecondDownloadIsNotTheBookWhoseSubtitleNamesAnotherVolume() {
        // "Saga (1).epub" may be volume 1, and the book says it is volume 2; only "Saga (2).epub" goes to it
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shellRows.add(new FilelessBookRow(8L, null, false, "cover", null, "Saga", "Volume Two", null, null, "Brian K. Vaughan"));
        LibraryFile first = newFile(lib, path, "V/Vaughan, Brian K.", "Saga (1).epub");
        LibraryFile second = newFile(lib, path, "V/Vaughan, Brian K.", "Saga (2).epub");
        Map<String, Decision> decisions = new LinkedHashMap<>();

        var result = rescan(lib, filelessDecisions(decisions), first, second);

        assertThat(attached(result)).containsExactly(Map.entry(8L, List.of(second)));
        assertThat(newBooks(result)).containsExactly(List.of("Saga (1).epub"));
        assertThat(decisions.get("Saga (1).epub").reason())
                .isEqualTo("book#8 \"Saga\" names volume 2 in its title or subtitle, and \" (1)\" may mean volume 1");
    }

    @Test
    void rescan_autoDetect_aSecondDownloadIsTheBookWhoseSubtitleHasABareNumber() {
        // "World War II" names no volume: the book has no series number, and "Unbroken (1).epub" is its copy
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shellRows.add(new FilelessBookRow(12L, null, false, "cover", null, "Unbroken",
                "A World War II Story of Survival, Resilience, and Redemption", null, null, "Laura Hillenbrand"));
        LibraryFile copy = newFile(lib, path, "H/Hillenbrand, Laura", "Unbroken (1).epub");
        Map<String, Decision> decisions = new LinkedHashMap<>();

        var result = rescan(lib, filelessDecisions(decisions), copy);

        assertThat(attached(result)).containsExactly(Map.entry(12L, List.of(copy)));
        assertThat(result.newBookGroups()).isEmpty();
        assertThat(decisions.get("Unbroken (1).epub").reason()).contains("read as a copy");
    }

    @Test
    void rescan_autoDetect_aSecondDownloadIsTheBookOfASeriesOfAnotherName() {
        // Volume 1 of The Handmaid's Tale would be named after it: "The Testaments (1).epub" is the copy of its #2,
        // where "Saga (1).epub" may be volume 1 of the series "Saga"
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shellRows.add(new FilelessBookRow(14L, null, false, "cover", null, "The Testaments", null, "The Handmaid's Tale", 2f,
                "Margaret Atwood"));
        shellRows.add(new FilelessBookRow(15L, null, false, "cover", null, "Saga", null, "Saga", 2f, "Brian K. Vaughan"));
        LibraryFile testaments = newFile(lib, path, "A/Atwood, Margaret", "The Testaments (1).epub");
        LibraryFile saga = newFile(lib, path, "V/Vaughan, Brian K.", "Saga (1).epub");
        Map<String, Decision> decisions = new LinkedHashMap<>();

        var result = rescan(lib, filelessDecisions(decisions), testaments, saga);

        assertThat(attached(result)).containsExactly(Map.entry(14L, List.of(testaments)));
        assertThat(newBooks(result)).containsExactly(List.of("Saga (1).epub"));
        assertThat(decisions.get("Saga (1).epub").reason())
                .isEqualTo("book#15 \"Saga\" is number 2 of a series of the same name, and \" (1)\" may mean volume 1");
    }

    /** "Saga" numbered 1 and "Saga" numbered 2, fileless: a "Saga.epub" and its "Saga (1).epub" say neither. */
    private void twoSagas(LibraryOrganizationMode mode) {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(mode, path);
        shellRows.add(new FilelessBookRow(1L, null, false, "cover", null, "Saga", null, null, 1f, "Brian K. Vaughan"));
        shellRows.add(new FilelessBookRow(2L, null, false, "cover", null, "Saga", null, null, 2f, "Brian K. Vaughan"));
        LibraryFile saga = newFile(lib, path, "V/Brian K. Vaughan", "Saga.epub");
        LibraryFile copy = newFile(lib, path, "V/Brian K. Vaughan", "Saga (1).epub");
        Map<String, Decision> decisions = new LinkedHashMap<>();

        var pair = rescan(lib, filelessDecisions(decisions), saga, copy);

        // The copy is no evidence for volume 1: read as a copy, book#2 qualifies as much as book#1
        assertThat(pair.filesToAttach()).isEmpty();
        assertThat(newBooks(pair)).containsExactly(List.of("Saga.epub", "Saga (1).epub"));
        assertThat(decisions.get("Saga.epub")).isInstanceOf(Ambiguous.class);
        assertThat(decisions.get("Saga (1).epub")).isEqualTo(new Ambiguous(List.of(1L, 2L),
                "2 fileless books qualify: book#1 \"Saga\", and book#2 \"Saga\" if \" (1)\" is a copy"));

        var alone = rescan(lib, copy);

        assertThat(alone.filesToAttach()).isEmpty();
        assertThat(newBooks(alone)).containsExactly(List.of("Saga (1).epub"));
    }

    @Test
    void rescan_autoDetect_aSecondDownloadOfATitleTwoVolumesShareGoesToNeither() {
        twoSagas(LibraryOrganizationMode.AUTO_DETECT);
    }

    @Test
    void rescan_bookPerFolder_aSecondDownloadOfATitleTwoVolumesShareGoesToNeither() {
        twoSagas(LibraryOrganizationMode.BOOK_PER_FOLDER);
    }

    @Test
    void rescan_autoDetect_aSecondVolumeMarkerFollowsItsOriginalOnly() {
        // " (2)" reads as the second volume on its own; next to its original it is a copy, and goes with it
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(920, "The Martian", "Andy Weir");
        shell(921, "Artemis", "Andy Weir");
        LibraryFile martian = newFile(lib, path, "W/Weir, Andy", "The Martian.epub");
        LibraryFile martianCopy = newFile(lib, path, "W/Weir, Andy", "The Martian (2).epub");
        LibraryFile artemis = newFile(lib, path, "W/Weir, Andy", "Artemis (2).epub");

        var result = rescan(lib, martian, martianCopy, artemis);

        assertThat(attached(result)).containsExactly(Map.entry(920L, List.of(martian, martianCopy)));
        assertThat(newBooks(result)).containsExactly(List.of("Artemis (2).epub"));
    }

    @Test
    void rescan_autoDetect_alikeFilelessBooksClaimedFromTwoFoldersGetNone() {
        // Both files go to the alike book chosen, book#12, which is then claimed from two folders
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(40, "Survival", "Ann Smith");
        shell(12, "Survival", "Ann Smith");
        LibraryFile first = newFile(lib, path, "S/Smith, Ann", "Survival.epub");
        LibraryFile second = newFile(lib, path, "S/Smith, Ann/Series", "Survival.epub");
        Map<Long, String> rejected = new LinkedHashMap<>();

        var result = rescan(lib, new BookGroupingService.DecisionListener() {
            @Override
            public void claimsRejected(long bookId, List<LibraryFile> files, String why) {
                rejected.put(bookId, why);
            }
        }, first, second);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(result.newBookGroups()).hasSize(2);
        assertThat(rejected).containsOnlyKeys(12L);
        assertThat(rejected.get(12L)).startsWith("claimed by files of 2 folders");
    }

    @Test
    void rescan_autoDetect_theFormatsOfOneFolderGoToTheOneAlikeFilelessBookChosen() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(12, true, "Survival", "Ann Smith");
        shell(40, "Survival", "Ann Smith");
        shell(41, "Survival", "Ann Smith");
        LibraryFile epub = newFile(lib, path, "S/Smith, Ann", "Survival.epub");
        LibraryFile pdf = newFile(lib, path, "S/Smith, Ann", "Survival.pdf");
        Map<String, Decision> decisions = new LinkedHashMap<>();

        var result = rescan(lib, filelessDecisions(decisions), epub, pdf);

        // book#12 is a paper copy's record: the lowest id of the others gets the files
        assertThat(attached(result)).containsExactly(Map.entry(40L, List.of(epub, pdf)));
        assertThat(kinds(result)).containsExactly(Map.entry(40L, Kind.FILELESS));
        assertThat(result.newBookGroups()).isEmpty();
        assertThat(decisions.get("Survival.epub").reason())
                .isEqualTo("title and author agree with book#40 \"Survival\", one of 3 alike fileless books (book#12, book#40, book#41)");
    }

    @Test
    void rescan_autoDetect_filelessBooksAtTwoPlacesOfASeriesAreNotAlike() {
        // 1.5 and 2.5 are no whole series numbers, and still two places in the series: the file says neither
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shellRows.add(new FilelessBookRow(5L, null, false, "cover", null, "Survival", null, null, 1.5f, "Ann Smith"));
        shellRows.add(new FilelessBookRow(9L, null, false, "cover", null, "Survival", null, null, 2.5f, "Ann Smith"));
        LibraryFile survival = newFile(lib, path, "S/Smith, Ann", "Survival.epub");
        Map<String, Decision> decisions = new LinkedHashMap<>();

        var result = rescan(lib, filelessDecisions(decisions), survival);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Survival.epub"));
        assertThat(decisions.get("Survival.epub").reason()).startsWith("2 fileless books qualify");
    }

    @Test
    void rescan_autoDetect_filelessEditionsOfTwoYearsAreNotAlike() {
        // The file names the 2023 edition; the editions' titles differ only by their years, which no identity holds
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(12, "Rick Steves Italy (2020)", "Rick Steves");
        shell(40, "Rick Steves Italy (2023)", "Rick Steves");
        LibraryFile italy = newFile(lib, path, "S/Steves, Rick", "Rick Steves Italy (2023).epub");
        Map<String, Decision> decisions = new LinkedHashMap<>();

        var result = rescan(lib, filelessDecisions(decisions), italy);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Rick Steves Italy (2023).epub"));
        assertThat(decisions.get("Rick Steves Italy (2023).epub").reason()).startsWith("2 fileless books qualify");
    }

    @Test
    void rescan_autoDetect_rootFilesAttachByExactTitleToo() {
        // New: a root file was never attached; "Dune.pdf" next to "Dune.epub" shares its sidecar files, and the
        // initial scan groups root files by exact title as well
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        bookIn(1000, path, "", "Dune.epub");
        LibraryFile pdf = newFile(lib, path, "", "Dune.pdf");
        LibraryFile messiah = newFile(lib, path, "", "Dune Messiah.pdf");

        var result = rescan(lib, pdf, messiah);

        assertThat(attached(result)).containsExactly(Map.entry(1000L, List.of(pdf)));
        assertThat(newBooks(result)).containsExactly(List.of("Dune Messiah.pdf"));
    }

    @Test
    void rescan_autoDetect_deletedBooksAndNonBookFilesAreNoCandidates() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        rows.add(new BookFileScanRow(1L, 1100L, 1L, "/books", "Author", "Gone.epub", true, BookFileType.EPUB, true));
        rows.add(new BookFileScanRow(2L, 1101L, 1L, "/books", "Author", "Notes.pdf", false, BookFileType.PDF, false));
        LibraryFile gone = newFile(lib, path, "Author", "Gone.pdf");
        LibraryFile notes = newFile(lib, path, "Author", "Notes.epub");

        var result = rescan(lib, gone, notes);

        assertThat(result.filesToAttach()).isEmpty();
        verifyNoQueryPerFolderOrFile(0);
    }

    @Test
    void rescan_aNumberTooLongForAnAudiobookPartDoesNotStopTheRescan() {
        // Parsed as an int, "Part 9780261102217" threw for every new file of the library, on every rescan
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        LibraryFile hobbit = newFile(lib, path, "Tolkien", "The Hobbit.epub");
        LibraryFile odd = newFile(lib, path, "Misc", "The Hobbit (Part 9780261102217).m4b");

        var result = rescan(lib, hobbit, odd);

        assertThat(newBooks(result)).containsExactlyInAnyOrder(
                List.of("The Hobbit.epub"), List.of("The Hobbit (Part 9780261102217).m4b"));
    }

    @Test
    void rescan_autoDetect_aNewDiscFolderJoinsTheBookOfTheOtherDiscs() {
        // "Tolkien/The Hobbit/Disc 3/*.mp3" is the folder audiobook "Disc 3" in "Tolkien/The Hobbit"
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        rows.add(new BookFileScanRow(4000L, 400L, 1L, "/books", "Tolkien/The Hobbit", "Disc 1", true, BookFileType.AUDIOBOOK, false));
        rows.add(new BookFileScanRow(4001L, 400L, 1L, "/books", "Tolkien/The Hobbit", "Disc 2", true, BookFileType.AUDIOBOOK, false));
        LibraryFile disc3 = folderAudiobook(path, "Tolkien/The Hobbit", "Disc 3");
        LibraryFile cd1 = folderAudiobook(path, "Tolkien/The Silmarillion", "CD1");
        LibraryFile cd2 = folderAudiobook(path, "Tolkien/The Silmarillion", "CD2");

        var result = rescan(lib, disc3, cd1, cd2);

        assertThat(attached(result)).containsExactly(Map.entry(400L, List.of(disc3)));
        assertThat(newBooks(result)).containsExactly(List.of("CD1", "CD2"));
    }

    @Test
    void rescan_autoDetect_anAuthorlessBookInTheFolderDoesNotTakeAFileNamingAnotherAuthor() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        bookIn(300, path, "", "Dune - F. Herbert.epub");
        LibraryFile brian = newFile(lib, path, "", "Dune - B. Herbert.epub");

        var result = rescan(lib, brian);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Dune - B. Herbert.epub"));
    }

    @Test
    void initialScan_theSameTitleByTwoAuthorsWrittenWithInitialsIsTwoBooks() {
        var path = pathEntity(1L, "/books");
        var lib = library(LibraryOrganizationMode.AUTO_DETECT, path);

        var groups = bookGroupingService.groupForInitialScan(List.of(
                ebook(path, "Poetry", "Collected Poems - W. B. Yeats.epub"),
                ebook(path, "Poetry", "Collected Poems - T. S. Eliot.epub")), lib);

        assertThat(groups).hasSize(2);
    }

    @Test
    void rescan_autoDetect_aFileTheBookTurnedDownForItsAuthorDoesNotFollowItsGroupIntoThatBook() {
        // "Collected Poems.pdf" names no one and matches the book; "Collected Poems - W. B. Yeats.epub" is of its
        // group, but names another author than the book's
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        bookIn(100, path, "Poetry", "Collected Poems - T. S. Eliot.epub");
        authorsOf(100, "T. S. Eliot");
        LibraryFile pdf = newFile(lib, path, "Poetry", "Collected Poems.pdf");
        LibraryFile yeats = newFile(lib, path, "Poetry", "Collected Poems - W. B. Yeats.epub");
        List<String> refused = new ArrayList<>();

        var result = rescan(lib, new BookGroupingService.DecisionListener() {
            @Override
            public void groupRefused(List<LibraryFile> files, long bookId, List<LibraryFile> refusing) {
                refusing.forEach(file -> refused.add(bookId + ":" + file.getFileName()));
            }
        }, pdf, yeats);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Collected Poems.pdf", "Collected Poems - W. B. Yeats.epub"));
        assertThat(refused).containsExactly("100:Collected Poems - W. B. Yeats.epub");
    }

    @Test
    void rescan_autoDetect_aFileTheFilelessBookTurnedDownDoesNotFollowItsGroupIntoIt() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(200, "Collected Poems", "T. S. Eliot");
        LibraryFile eliot = newFile(lib, path, "Poetry/T. S. Eliot", "Collected Poems.epub");
        LibraryFile yeats = newFile(lib, path, "Poetry/T. S. Eliot", "Collected Poems - W. B. Yeats.pdf");

        var result = rescan(lib, eliot, yeats);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Collected Poems.epub", "Collected Poems - W. B. Yeats.pdf"));
    }

    @Test
    void rescan_autoDetect_twoDifferentEpubsOfOneGroupDoNotGoToAFilelessBookOnlyOneOfThemClaimed() {
        // D2: two different files of one format claiming a fileless book get none of it. "Survival.epub" is turned
        // down by the one-word rule ("J" is not "Jane"), yet it would follow the other epub of its group
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(700, "Survival", "Jane Smith");
        LibraryFile bare = newFile(lib, path, "S/Smith, J", "Survival.epub");
        LibraryFile named = newFile(lib, path, "S/Smith, J", "Survival - Jane Smith.epub");

        var result = rescan(lib, bare, named);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Survival.epub", "Survival - Jane Smith.epub"));
    }

    @Test
    void rescan_autoDetect_followersOfAFilelessClaimAreClaimsToo() {
        // The pdf has no claim of its own but goes with the epub, from the epub's folder: one book, two formats
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(710, "Survival", "Jane Smith");
        LibraryFile epub = newFile(lib, path, "S/Smith, J", "Survival - Jane Smith.epub");
        LibraryFile pdf = newFile(lib, path, "S/Smith, J", "Survival.pdf");

        var result = rescan(lib, epub, pdf);

        assertThat(attached(result)).containsExactly(Map.entry(710L, List.of(epub, pdf)));
    }

    @Test
    void rescan_bookPerFolder_aFolderAudiobookIsTheBookOfItsOwnFolderNotOfTheFolderAboveIt() {
        // "Author/Book One/" was imported as a folder audiobook: its row is in "Author", named "Book One"
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        rows.add(new BookFileScanRow(5000L, 500L, 1L, "/books", "Author", "Book One", true, BookFileType.AUDIOBOOK, false, true));
        LibraryFile bookTwo = folderAudiobook(path, "Author", "Book Two");
        LibraryFile inBookOne = newFile(lib, path, "Author/Book One", "Book One.epub");

        var result = rescan(lib, bookTwo, inBookOne);

        assertThat(attached(result)).containsExactly(Map.entry(500L, List.of(inBookOne)));
        assertThat(newBooks(result)).containsExactly(List.of("Book Two"));
    }

    @Test
    void rescan_bookPerFolder_anAudiobookBelowTwoBooksWithEbooksAttachesToNeither() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        bookIn(10, path, "Author/Series", "Book One.epub");
        bookIn(20, path, "Author/Series", "Book Two.epub");
        LibraryFile audio = newFile(lib, path, "Author/Series/audio", "Book Two.m4b");

        var result = rescan(lib, audio);

        assertThat(attached(result)).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Book Two.m4b"));
    }

    @Test
    void rescan_bookPerFolder_aFileAtTheRootIsABookOfItsOwn() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        bookIn(10, path, "", "Dune.epub");
        LibraryFile other = newFile(lib, path, "", "Neuromancer.epub");

        var result = rescan(lib, other);

        assertThat(attached(result)).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Neuromancer.epub"));
    }

    @Test
    void rescan_bookPerFile_aNewFileIsNeverAttachedToABookWithFiles() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FILE, path);
        bookIn(10, path, "Frank Herbert", "Dune.epub");
        LibraryFile pdf = newFile(lib, path, "Frank Herbert", "Dune.pdf");

        var result = rescan(lib, pdf);

        assertThat(attached(result)).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Dune.pdf"));
    }

    @Test
    void rescan_autoDetect_aFileTwoBooksOfItsFolderReadAsIsNotGivenToAFilelessBookEither() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        bookIn(10, path, "Frank Herbert", "Dune.epub");
        bookIn(20, path, "Frank Herbert", "Dune.mobi");
        shell(300, "Dune", "Frank Herbert");
        LibraryFile pdf = newFile(lib, path, "Frank Herbert", "Dune.pdf");
        List<LibraryFile> askedFileless = new ArrayList<>();

        var result = rescan(lib, new BookGroupingService.DecisionListener() {
            @Override
            public void fileless(LibraryFile file, org.booklore.service.library.matching.ExistingBookMatcher.Decision decision) {
                askedFileless.add(file);
            }
        }, pdf);

        assertThat(attached(result)).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Dune.pdf"));
        assertThat(askedFileless).isEmpty();
    }

    @Test
    void rescan_autoDetect_filesOfOneNewBookThatMatchDifferentBooksBecomeANewBook() {
        // "The Lost Hero (Part 1)" as an epub reads as volume 1, as an m4b as part 1 of "The Lost Hero"; they share a
        // base name, so they are one new book (D10), yet each matches another book of the folder
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        bookIn(10, path, "Rick Riordan", "The Lost Hero 1.epub");
        bookIn(20, path, "Rick Riordan", "The Lost Hero.m4b");
        LibraryFile epub = newFile(lib, path, "Rick Riordan", "The Lost Hero (Part 1).epub");
        LibraryFile m4b = newFile(lib, path, "Rick Riordan", "The Lost Hero (Part 1).m4b");
        List<java.util.Set<Long>> splits = new ArrayList<>();

        var result = rescan(lib, new BookGroupingService.DecisionListener() {
            @Override
            public void groupSplit(List<LibraryFile> files, java.util.Set<Long> bookIds) {
                splits.add(bookIds);
            }
        }, epub, m4b);

        assertThat(attached(result)).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("The Lost Hero (Part 1).epub", "The Lost Hero (Part 1).m4b"));
        assertThat(splits).containsExactly(java.util.Set.of(10L, 20L));
    }

    @Test
    void rescan_lookupsAreScopedToOneCall() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        LibraryFile file = newFile(lib, path, "Author/Title", "Title.epub");

        rescan(lib, file);
        bookGroupingService.groupForRescan(List.of(file), lib, new LibraryBookFiles(rows));

        verify(bookRepository, times(2)).findFilelessBookRowsByLibraryId(1L);
    }

    @Test
    void rescan_noNewFiles_queriesNothing() {
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, pathEntity(1L, "/books"));

        var result = bookGroupingService.groupForRescan(List.of(), lib, new LibraryBookFiles(List.of()));

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(result.newBookGroups()).isEmpty();
        verifyNoMoreInteractions(bookRepository);
    }

    @Test
    void rescan_twentyThousandFilesAgainstTwentyThousandFilelessBooksTakeWellUnderTenSeconds() {
        // Before, each file was compared with every fileless book (about 2.5 us a pair) and each new folder queried
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        for (int i = 0; i < 20_000; i++) {
            shell(100_000 + i, "Title Number " + i + " Of Many", "Author" + (char) ('a' + i % 26) + " Person");
            bookIn(i + 1, path, "A/Author " + i, "Existing " + i + ".epub");
            authorsOf(i + 1, "Author " + i);
        }
        LibraryFile[] files = new LibraryFile[20_000];
        for (int i = 0; i < files.length; i++) {
            files[i] = newFile(lib, path, "A/Author " + i, "New Title " + i + ".epub");
        }

        long start = System.nanoTime();
        var result = rescan(lib, files);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(result.newBookGroups()).hasSize(20_000);
        assertThat(millis).isLessThan(10_000);
        verifyNoQueryPerFolderOrFile(20_000 / BookGroupingService.AUTHOR_QUERY_BATCH);
    }

    // ---- weak matches (one of alike fileless books, a " (1)" read as a copy) only add a book to files ----

    /** What the rescan tells its listener of groups and claims, one line each. */
    private static final class Outcomes implements BookGroupingService.DecisionListener {
        final Map<String, Decision> fileless = new LinkedHashMap<>();
        final List<String> lines = new ArrayList<>();

        @Override
        public void fileless(LibraryFile file, Decision decision) {
            fileless.put(file.getFileName(), decision);
        }

        @Override
        public void claimsRejected(long bookId, List<LibraryFile> files, String why) {
            lines.add("rejected " + bookId + " " + names(files));
        }

        @Override
        public void claimsYielded(long bookId, List<LibraryFile> files, String why) {
            lines.add("yielded " + bookId + " " + names(files) + ": " + why);
        }

        @Override
        public void groupSplit(List<LibraryFile> files, java.util.Set<Long> bookIds) {
            lines.add("split " + bookIds + " " + names(files));
        }

        @Override
        public void groupRefused(List<LibraryFile> files, long bookId, List<LibraryFile> refusing) {
            lines.add("refused " + bookId + " by " + names(refusing));
        }

        private static List<String> names(List<LibraryFile> files) {
            return files.stream().map(LibraryFile::getFileName).toList();
        }

        Match weakMatch(String fileName) {
            assertThat(fileless.get(fileName)).isInstanceOf(Match.class);
            Match match = (Match) fileless.get(fileName);
            assertThat(match.weak()).as(fileName + " matched weakly").isTrue();
            return match;
        }
    }

    @Test
    void rescan_autoDetect_aWeakMatchYieldsToTheStrongMatchOfItsGroup() {
        // The dry run: "Killing Floor.epub" weakly took one of two alike fileless "Killing Floor"s, which split its
        // group from the book of "The Killing Floor (1).epub" in their folder; before, both went to that book
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        bookIn(280632, path, "C/Lee Child/Jack Reacher", "01. Killing Floor - Lee Child (2006).mobi");
        authorsOf(280632, "Lee Child");
        shell(10, "Killing Floor", "Lee Child");
        shell(11, "Killing Floor", "Lee Child");
        LibraryFile copy = newFile(lib, path, "C/Lee Child/Jack Reacher", "The Killing Floor (1).epub");
        LibraryFile plain = newFile(lib, path, "C/Lee Child/Jack Reacher", "Killing Floor.epub");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, copy, plain);

        assertThat(attached(result)).containsExactly(Map.entry(280632L, List.of(copy, plain)));
        assertThat(kinds(result)).containsExactly(Map.entry(280632L, Kind.SAME_FOLDER));
        assertThat(result.newBookGroups()).isEmpty();
        assertThat(outcomes.weakMatch("Killing Floor.epub").bookId()).isEqualTo(10);
        assertThat(outcomes.lines).isEmpty();
    }

    @Test
    void rescan_bookPerFolder_aWeakMatchYieldsToTheStrongMatchOfItsFolder() {
        // "Survival.pdf" is weakly one of two alike "Survival"s; its folder is "Survival 1", by the epub's name
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        shell(10, "Survival", "Ann Smith");
        shell(11, "Survival", "Ann Smith");
        shell(20, "Survival 1", "Ann Smith");
        LibraryFile first = newFile(lib, path, "S/Smith, Ann/Survival", "Survival 1.epub");
        LibraryFile plain = newFile(lib, path, "S/Smith, Ann/Survival", "Survival.pdf");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, first, plain);

        assertThat(attached(result)).containsExactly(Map.entry(20L, List.of(first, plain)));
        assertThat(outcomes.weakMatch("Survival.pdf").bookId()).isEqualTo(10);
        assertThat(outcomes.lines).isEmpty();
    }

    @Test
    void rescan_bookPerFolder_weakMatchesOfAGroupWithoutAStrongOneMustAgree() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        shell(10, "Survival", "Ann Smith");
        shell(11, "Survival", "Ann Smith");
        shell(20, "The Martian", "Ann Smith");
        LibraryFile survival = newFile(lib, path, "S/Smith, Ann/Mixed", "Survival.epub");
        LibraryFile martian = newFile(lib, path, "S/Smith, Ann/Mixed", "The Martian (1).epub");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, survival, martian);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Survival.epub", "The Martian (1).epub"));
        assertThat(outcomes.weakMatch("Survival.epub").bookId()).isEqualTo(10);
        assertThat(outcomes.weakMatch("The Martian (1).epub").bookId()).isEqualTo(20);
        assertThat(outcomes.lines).containsExactly("split [10, 20] [Survival.epub, The Martian (1).epub]");
        // Weak matches that agree give the folder their book
        var agreeing = rescan(lib, newFile(lib, path, "S/Smith, Ann/Mixed", "Survival.epub"),
                newFile(lib, path, "S/Smith, Ann/Mixed", "Survival (1).pdf"));
        assertThat(attached(agreeing)).containsOnlyKeys(10L);
        assertThat(attached(agreeing).get(10L)).hasSize(2);
    }

    @Test
    void rescan_bookPerFolder_aWeakMatchAGroupMateTurnsDownGivesTheGroupNoBook() {
        // "03. The Long Night.epub" says it is number 3 of a series; the book in no series is not that
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        shell(30, "The Long Night", "Jane Doe");
        LibraryFile copy = newFile(lib, path, "D/Doe, Jane/Night", "The Long Night (1).epub");
        LibraryFile third = newFile(lib, path, "D/Doe, Jane/Night", "03. The Long Night.epub");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, copy, third);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("The Long Night (1).epub", "03. The Long Night.epub"));
        assertThat(outcomes.weakMatch("The Long Night (1).epub").bookId()).isEqualTo(30);
        assertThat(outcomes.lines).containsExactly("refused 30 by [03. The Long Night.epub]");
    }

    @Test
    void rescan_bookPerFolder_aFileWhoseWeakMatchYieldsStillTurnsDownWhatItsNameSaysItIsNot() {
        // Its series reading says it is number 3, so not "The Long Night 1" number 1, which the epub matched; read as
        // a copy it is "The Long Night" number 3. Yielding, it is what its name says without the copy reading
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        shellRows.add(new FilelessBookRow(31L, null, false, "cover", null, "The Long Night 1", null, null, 1f, "Jane Doe"));
        shellRows.add(new FilelessBookRow(32L, null, false, "cover", null, "The Long Night", null, null, 3f, "Jane Doe"));
        LibraryFile first = newFile(lib, path, "D/Doe, Jane/Night", "The Long Night 1.epub");
        LibraryFile third = newFile(lib, path, "D/Doe, Jane/Night", "03. The Long Night (1).pdf");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, first, third);

        assertThat(result.filesToAttach()).isEmpty();
        Match weak = outcomes.weakMatch("03. The Long Night (1).pdf");
        assertThat(weak.bookId()).isEqualTo(32);
        assertThat(weak.refused()).containsExactly(31L);
        assertThat(outcomes.lines).containsExactly("refused 31 by [03. The Long Night (1).pdf]");
    }

    @Test
    void rescan_autoDetect_whatOnlyACopyReadingSaysAgainstABookDoesNotTakeItFromTheGroup() {
        // The pdf read as a copy is not "Good Omens" by Neil Gaiman; before that reading it found no book and refused
        // none, and followed the epub. It still does
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(10, "Good Omens", "Neil Gaiman");
        LibraryFile epub = newFile(lib, path, "G/Neil Gaiman", "Good Omens.epub");
        LibraryFile pdf = newFile(lib, path, "G/Neil Gaiman", "Good Omens - T. Pratchett (1).pdf");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, epub, pdf);

        assertThat(attached(result)).containsExactly(Map.entry(10L, List.of(epub, pdf)));
        assertThat(outcomes.fileless.get(pdf.getFileName())).isEqualTo(new NoMatch(
                "book#10 \"Good Omens\" is not by 'T. Pratchett' (\" (1)\" read as a copy)", Set.of(), Set.of(10L)));
        assertThat(outcomes.lines).isEmpty();
    }

    @Test
    void rescan_autoDetect_whatOnlyACopyReadingSaysAgainstABookKeepsAWeakGroupFromIt() {
        // Without "Good Omens.epub", only the weak match of "Good Omens (1).epub" gives the group the book, and the
        // pdf says it is not that book: before the copy reading neither got a book, and they get none now
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(10, "Good Omens", "Neil Gaiman");
        LibraryFile copy = newFile(lib, path, "G/Neil Gaiman", "Good Omens (1).epub");
        LibraryFile pdf = newFile(lib, path, "G/Neil Gaiman", "Good Omens - T. Pratchett (1).pdf");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, copy, pdf);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Good Omens (1).epub", "Good Omens - T. Pratchett (1).pdf"));
        assertThat(outcomes.weakMatch("Good Omens (1).epub").bookId()).isEqualTo(10);
        assertThat(outcomes.lines).containsExactly("refused 10 by [Good Omens - T. Pratchett (1).pdf]");
    }

    @Test
    void rescan_autoDetect_aFileAmbiguousOnlyAsACopyStillTurnsDownWhatItsNameSaysItIsNot() {
        // The pdf's name is not book#10 by Neil Gaiman, which "Good Omens.epub" matched; read as a copy it is book#20,
        // or the #2 book#21 if its " (1)" is a copy. Ambiguous, it refuses book#10 as its NoMatch did before
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shellRows.add(new FilelessBookRow(10L, null, false, "cover", null, "Good Omens", "Book 1", null, null, "Neil Gaiman"));
        shell(20, "Good Omens", "T. Pratchett");
        shellRows.add(new FilelessBookRow(21L, null, false, "cover", null, "Good Omens", null, null, 2f, "Terry Pratchett"));
        LibraryFile epub = newFile(lib, path, "G/Neil Gaiman", "Good Omens.epub");
        LibraryFile pdf = newFile(lib, path, "G/Neil Gaiman", "Good Omens - T. Pratchett (1).pdf");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, epub, pdf);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Good Omens.epub", "Good Omens - T. Pratchett (1).pdf"));
        Decision ambiguous = outcomes.fileless.get(pdf.getFileName());
        assertThat(ambiguous).isInstanceOf(Ambiguous.class);
        assertThat(((Ambiguous) ambiguous).bookIds()).containsExactly(20L, 21L);
        assertThat(ambiguous.refused()).containsExactly(10L);
        assertThat(outcomes.lines).containsExactly("refused 10 by [Good Omens - T. Pratchett (1).pdf]");
    }

    @Test
    void rescan_autoDetect_aFileAmbiguousOnlyAsACopyAmongBooksNotAlikeStillTurnsDownWhatItsNameSaysItIsNot() {
        // As above, but read as a copy the pdf is book#20 or book#21, neither of which the " (1)" guard turns down,
        // and which are not alike ("Good Omens: A Novel" has another title). Ambiguous so, it still refuses book#10
        // as its NoMatch did before, and keeps "Good Omens.epub" from it
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shellRows.add(new FilelessBookRow(10L, null, false, "cover", null, "Good Omens", "Book 1", null, null, "Neil Gaiman"));
        shell(20, "Good Omens", "T. Pratchett");
        shellRows.add(new FilelessBookRow(21L, null, false, "cover", null, "Good Omens", "A Novel", null, null, "T. Pratchett"));
        LibraryFile epub = newFile(lib, path, "G/Neil Gaiman", "Good Omens.epub");
        LibraryFile pdf = newFile(lib, path, "G/Neil Gaiman", "Good Omens - T. Pratchett (1).pdf");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, epub, pdf);

        assertThat(outcomes.fileless.get(epub.getFileName())).isInstanceOf(Match.class)
                .extracting(decision -> ((Match) decision).bookId()).isEqualTo(10L);
        assertThat(outcomes.fileless.get(pdf.getFileName())).isEqualTo(new Ambiguous(List.of(20L, 21L),
                "2 fileless books qualify: book#20 \"Good Omens\", book#21 \"Good Omens\"", Set.of(10L), Set.of()));
        assertThat(result.filesToAttach()).isEmpty();
        assertThat(newBooks(result)).containsExactly(List.of("Good Omens.epub", "Good Omens - T. Pratchett (1).pdf"));
        assertThat(outcomes.lines).containsExactly("refused 10 by [Good Omens - T. Pratchett (1).pdf]");
    }

    @Test
    void rescan_bookPerFolder_aWeakMatchToItsGroupsBookClaimsItFromTheFolderOfTheFileItFollows() {
        // groupByFolder puts the audiobook of a subfolder with the ebook of the folder above. Its " (1)" read as a
        // copy, it weakly matches the book the epub matched; before, it had no match and followed, claiming from the
        // epub's folder. Claiming from its own, it would make the book claimed from two folders
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        shell(20, "Survival", "Kate Atkinson");
        LibraryFile audio = newFile(lib, path, "A/Kate Atkinson/Survival/Audio", "Survival (1).m4b");
        LibraryFile epub = newFile(lib, path, "A/Kate Atkinson/Survival", "Survival.epub");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, audio, epub);

        assertThat(attached(result)).containsExactly(Map.entry(20L, List.of(audio, epub)));
        assertThat(outcomes.weakMatch("Survival (1).m4b").bookId()).isEqualTo(20);
        assertThat(outcomes.lines).isEmpty();
        // A strong match of its own in the subfolder claims from there, as before
        LibraryFile strongAudio = newFile(lib, path, "A/Kate Atkinson/Survival/Audio", "Survival.m4b");
        Outcomes strong = new Outcomes();
        assertThat(rescan(lib, strong, strongAudio, epub).filesToAttach()).isEmpty();
        assertThat(strong.lines).singleElement().asString().startsWith("rejected 20");
    }

    @Test
    void rescan_autoDetect_aWeakClaimFromAnotherFolderYieldsToTheStrongOne() {
        // The dry run: both claimed "MEG", from two folders, and neither got it; before, "The MEG.epub" did
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(70, "MEG", "Steve Alten");
        LibraryFile meg = newFile(lib, path, "A/Steve Alten/MEG", "The MEG.epub");
        LibraryFile copy = newFile(lib, path, "A/Steve Alten", "MEG (1).epub");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, meg, copy);

        assertThat(attached(result)).containsExactly(Map.entry(70L, List.of(meg)));
        assertThat(newBooks(result)).containsExactly(List.of("MEG (1).epub"));
        outcomes.weakMatch("MEG (1).epub");
        assertThat(outcomes.lines).containsExactly("yielded 70 [MEG (1).epub]: claimed by files of 2 folders"
                + " [1:A/Steve Alten, 1:A/Steve Alten/MEG]: The MEG.epub, MEG (1).epub; the weak claims of MEG (1).epub"
                + " yield to the others");
    }

    @Test
    void rescan_autoDetect_aWeakClaimOfAnotherFileOfOneFormatYieldsToTheStrongGroup() {
        // The dry run: the first two are one group, which "The Subtle Knife.epub" gave the book; its copy's own
        // match is weak, but it goes with its group. The third reads as number 2 only with " (1)" read as a copy
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shellRows.add(new FilelessBookRow(71L, null, false, "cover", null, "The Subtle Knife", null, "His Dark Materials", 2f,
                "Philip Pullman"));
        String folder = "P/Philip Pullman/His Dark Materials";
        LibraryFile knife = newFile(lib, path, folder, "The Subtle Knife.epub");
        LibraryFile copy = newFile(lib, path, folder, "The Subtle Knife (1).epub");
        LibraryFile numbered = newFile(lib, path, folder, "02. The Subtle Knife - Philip Pullman (2001) (1).epub");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, knife, copy, numbered);

        assertThat(attached(result)).containsExactly(Map.entry(71L, List.of(knife, copy)));
        assertThat(newBooks(result)).containsExactly(List.of("02. The Subtle Knife - Philip Pullman (2001) (1).epub"));
        outcomes.weakMatch("The Subtle Knife (1).epub");
        outcomes.weakMatch("02. The Subtle Knife - Philip Pullman (2001) (1).epub");
        assertThat(outcomes.lines).singleElement().asString()
                .startsWith("yielded 71 [02. The Subtle Knife - Philip Pullman (2001) (1).epub]: claimed by different files of one format");
    }

    @Test
    void rescan_autoDetect_strongClaimsThatDoNotAgreeLeaveTheBookAloneAsBefore() {
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.AUTO_DETECT, path);
        shell(500, "Survival", "Ann Smith");
        LibraryFile first = newFile(lib, path, "S/Smith, Ann", "Survival.epub");
        LibraryFile second = newFile(lib, path, "S/Smith, Ann/Series", "Survival.epub");
        LibraryFile copy = newFile(lib, path, "S/Smith, Ann/Other", "Survival (1).epub");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, first, second, copy);

        assertThat(result.filesToAttach()).isEmpty();
        assertThat(result.newBookGroups()).hasSize(3);
        assertThat(outcomes.lines).singleElement().asString().startsWith("rejected 500");
    }

    @Test
    void rescan_bookPerFolder_theFilesOfAGroupWhoseBookOnlyAWeakMatchGaveClaimItWeakly() {
        // "Notes.pdf" has no match of its own and follows "MEG (1).epub" of its folder, as weakly
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER, path);
        shell(70, "MEG", "Steve Alten");
        LibraryFile meg = newFile(lib, path, "A/Steve Alten/MEG", "The MEG.epub");
        LibraryFile copy = newFile(lib, path, "A/Steve Alten/Sharks", "MEG (1).epub");
        LibraryFile notes = newFile(lib, path, "A/Steve Alten/Sharks", "Notes.pdf");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, meg, copy, notes);

        assertThat(attached(result)).containsExactly(Map.entry(70L, List.of(meg)));
        assertThat(newBooks(result)).containsExactly(List.of("MEG (1).epub", "Notes.pdf"));
        assertThat(outcomes.lines).singleElement().asString().startsWith("yielded 70 [MEG (1).epub, Notes.pdf]");
        // Without the strong claim, the folder's weak claims are the book's
        var alone = rescan(lib, copy, notes);
        assertThat(attached(alone)).containsExactly(Map.entry(70L, List.of(copy, notes)));
    }

    @Test
    void rescan_bookPerFile_aWeakClaimYieldsToTheStrongOne() {
        // Every file is a book of its own: two files claiming one book leave it alone, unless only one is strong
        var path = pathEntity(1L, "/books");
        var lib = rescanLibrary(LibraryOrganizationMode.BOOK_PER_FILE, path);
        shell(300, "Dune", "Frank Herbert");
        LibraryFile dune = newFile(lib, path, "Frank Herbert", "Dune.epub");
        LibraryFile copy = newFile(lib, path, "Frank Herbert", "Dune (1).epub");
        Outcomes outcomes = new Outcomes();

        var result = rescan(lib, outcomes, dune, copy);

        assertThat(attached(result)).containsExactly(Map.entry(300L, List.of(dune)));
        assertThat(newBooks(result)).containsExactly(List.of("Dune (1).epub"));
        outcomes.weakMatch("Dune (1).epub");
        assertThat(outcomes.lines).singleElement().asString().startsWith("yielded 300 [Dune (1).epub]");
        // Two weak claims still leave it alone
        var weakOnly = rescan(lib, copy, newFile(lib, path, "Frank Herbert", "Dune (1).pdf"));
        assertThat(weakOnly.filesToAttach()).isEmpty();
    }
}
