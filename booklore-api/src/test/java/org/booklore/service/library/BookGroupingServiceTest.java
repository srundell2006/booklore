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
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
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
            shellRows.add(new FilelessBookRow(id, null, physical, "cover", null, title, null, null, null));
        }
        for (String author : authors) {
            shellRows.add(new FilelessBookRow(id, null, physical, "cover", null, title, null, null, author));
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
        // A copy reads as another number on its own; it follows its original to the fileless book
        shell(901, "The Martian", "Andy Weir");
        LibraryFile martian = newFile(lib, path, "W/Weir, Andy", "The Martian.epub");
        LibraryFile martianCopy = newFile(lib, path, "W/Weir, Andy", "The Martian (1).epub");

        var result = rescan(lib, copy, martian, martianCopy);

        assertThat(attached(result)).containsExactly(
                Map.entry(900L, List.of(copy)),
                Map.entry(901L, List.of(martian, martianCopy)));
        assertThat(result.newBookGroups()).isEmpty();
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
}
