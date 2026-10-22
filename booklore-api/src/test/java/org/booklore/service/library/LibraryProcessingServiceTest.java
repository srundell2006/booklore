package org.booklore.service.library;

import org.booklore.exception.APIException;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.websocket.LogNotification;
import org.booklore.model.websocket.Severity;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.service.NotificationService;
import org.booklore.service.library.importer.ImportSummary;
import org.booklore.service.library.matching.ExistingBookMatcher;
import org.booklore.task.options.RescanLibraryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LibraryProcessingServiceTest {

    private static final long LIBRARY_ID = 1L;

    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private FileAsBookProcessor fileAsBookProcessor;
    @Mock
    private BookRestorationService bookRestorationService;
    @Mock
    private BookDeletionService bookDeletionService;
    @Mock
    private LibraryFileHelper libraryFileHelper;
    @Mock
    private BookGroupingService bookGroupingService;
    @Mock
    private ExistingBookAttacher existingBookAttacher;

    private LibraryProcessingService libraryProcessingService;

    @TempDir
    Path tempDir;

    private LibraryEntity libraryEntity;
    private LibraryPathEntity pathEntity;

    @BeforeEach
    void setUp() throws IOException {
        libraryProcessingService = new LibraryProcessingService(
                libraryRepository,
                notificationService,
                bookAdditionalFileRepository,
                bookRepository,
                fileAsBookProcessor,
                bookRestorationService,
                bookDeletionService,
                libraryFileHelper,
                bookGroupingService,
                existingBookAttacher
        );

        Path libraryRoot = Files.createDirectory(tempDir.resolve("library"));
        pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath(libraryRoot.toString());

        libraryEntity = new LibraryEntity();
        libraryEntity.setId(LIBRARY_ID);
        libraryEntity.setName("Test Library");
        libraryEntity.setLibraryPaths(List.of(pathEntity));
    }

    private LibraryFile libraryFile(String subPath, String fileName) {
        return libraryFile(subPath, fileName, BookFileType.EPUB);
    }

    private LibraryFile libraryFile(String subPath, String fileName, BookFileType type) {
        return LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath(subPath)
                .fileName(fileName)
                .bookFileType(type)
                .build();
    }

    private BookFileScanRow row(long fileId, long bookId, String subPath, String fileName) {
        return row(fileId, bookId, subPath, fileName, BookFileType.EPUB, true, false);
    }

    private BookFileScanRow row(long fileId, long bookId, String subPath, String fileName,
                                BookFileType type, boolean bookFormat, boolean bookDeleted) {
        return new BookFileScanRow(fileId, bookId, pathEntity.getId(), pathEntity.getPath(), subPath, fileName,
                bookFormat, type, bookDeleted);
    }

    /** Coverage for a walk during which none of these rows changed. */
    private static WalkCoverage unchangedSinceWalkStart(LibraryBookFiles bookFiles, LibraryScanResult scan) {
        return new WalkCoverage(Set.copyOf(bookFiles.rows()), scan);
    }

    private void givenLibrary() {
        when(libraryRepository.findById(LIBRARY_ID)).thenReturn(Optional.of(libraryEntity));
    }

    private void givenRows(BookFileScanRow... rows) {
        when(bookAdditionalFileRepository.findScanRowsByLibraryId(LIBRARY_ID)).thenReturn(List.of(rows));
    }

    private void givenScan(LibraryScanResult scanResult) throws IOException {
        when(libraryFileHelper.scanAllLibraryFiles(libraryEntity)).thenReturn(scanResult);
        when(libraryFileHelper.filterByAllowedFormats(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void givenScan(LibraryFile... files) throws IOException {
        givenScan(new LibraryScanResult(List.of(files), Set.of()));
    }

    private void givenNothingToGroup() {
        when(bookGroupingService.groupForRescan(anyList(), eq(libraryEntity), any()))
                .thenReturn(new BookGroupingService.GroupingResult(List.of(), Collections.emptyMap()));
    }

    private void givenInitialScanGroupsPerFile() {
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity)))
                .thenAnswer(invocation -> {
                    List<LibraryFile> files = invocation.getArgument(0);
                    Map<String, List<LibraryFile>> result = new LinkedHashMap<>();
                    for (LibraryFile f : files) {
                        result.put(f.getFileName(), List.of(f));
                    }
                    return result;
                });
    }

    private void rescan() throws IOException {
        libraryProcessingService.rescanLibrary(RescanLibraryContext.builder().libraryId(LIBRARY_ID).build());
    }

    @SuppressWarnings("unchecked")
    private List<LibraryFile> filesPassedToRescanGrouping() {
        ArgumentCaptor<List<LibraryFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookGroupingService).groupForRescan(captor.capture(), eq(libraryEntity), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<LibraryFile> filesPassedToInitialGrouping() {
        ArgumentCaptor<List<LibraryFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookGroupingService).groupForInitialScan(captor.capture(), eq(libraryEntity));
        return captor.getValue();
    }

    // ---- initial scan ----

    @Test
    void processLibrary_shouldOnlyProcessNewFiles() throws IOException {
        givenLibrary();
        givenRows(row(1, 11, "", "book1.epub"));
        when(libraryFileHelper.getLibraryFiles(libraryEntity))
                .thenReturn(List.of(libraryFile("", "book1.epub"), libraryFile("", "book2.epub")));
        givenInitialScanGroupsPerFile();

        libraryProcessingService.processLibrary(LIBRARY_ID);

        assertThat(filesPassedToInitialGrouping()).extracting(LibraryFile::getFileName).containsExactly("book2.epub");
        verify(fileAsBookProcessor).processLibraryFilesGrouped(anyMap(), eq(libraryEntity));
    }

    @Test
    void processLibrary_noNewFiles_shouldProcessNothing() throws IOException {
        givenLibrary();
        givenRows(row(1, 11, "", "book1.epub"));
        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(libraryFile("", "book1.epub")));
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity))).thenReturn(Collections.emptyMap());

        libraryProcessingService.processLibrary(LIBRARY_ID);

        assertThat(filesPassedToInitialGrouping()).isEmpty();
    }

    @Test
    void processLibrary_allNewFiles_shouldProcessAll() throws IOException {
        givenLibrary();
        givenRows();
        when(libraryFileHelper.getLibraryFiles(libraryEntity))
                .thenReturn(List.of(libraryFile("", "book1.epub"), libraryFile("", "book2.epub")));
        givenInitialScanGroupsPerFile();

        libraryProcessingService.processLibrary(LIBRARY_ID);

        assertThat(filesPassedToInitialGrouping()).hasSize(2);
    }

    @Test
    void processLibrary_newFileInSubdirectory_shouldProcess() throws IOException {
        givenLibrary();
        givenRows(row(1, 11, "", "book1.epub"));
        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(libraryFile("fantasy", "book1.epub")));
        givenInitialScanGroupsPerFile();

        libraryProcessingService.processLibrary(LIBRARY_ID);

        List<LibraryFile> processedFiles = filesPassedToInitialGrouping();
        assertThat(processedFiles).hasSize(1);
        assertThat(processedFiles.getFirst().getFileName()).isEqualTo("book1.epub");
        assertThat(processedFiles.getFirst().getFileSubPath()).isEqualTo("fantasy");
    }

    @Test
    void processLibrary_additionalFile_shouldNotProcess() throws IOException {
        givenLibrary();
        givenRows(row(1, 11, "", "main.epub"), row(2, 11, "", "extra.pdf", BookFileType.PDF, true, false));
        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(libraryFile("", "extra.pdf")));
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity))).thenReturn(Collections.emptyMap());

        libraryProcessingService.processLibrary(LIBRARY_ID);

        assertThat(filesPassedToInitialGrouping()).isEmpty();
    }

    @Test
    void processLibrary_readsExistingFilesThroughTheProjectionOnly() throws IOException {
        givenLibrary();
        givenRows(row(1, 11, "", "book1.epub"));
        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(libraryFile("", "book1.epub")));
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity))).thenReturn(Collections.emptyMap());

        libraryProcessingService.processLibrary(LIBRARY_ID);

        verify(bookAdditionalFileRepository).findScanRowsByLibraryId(LIBRARY_ID);
        verifyNoMoreInteractions(bookAdditionalFileRepository);
        verifyNoInteractions(bookRepository);
    }

    // ---- rescan: guards and structure ----

    @Test
    void rescanLibrary_shouldAbortWhenPathNotAccessible() {
        pathEntity.setPath(tempDir.resolve("non_existent_path").toString());
        givenLibrary();

        RescanLibraryContext context = RescanLibraryContext.builder().libraryId(LIBRARY_ID).build();

        assertThatThrownBy(() -> libraryProcessingService.rescanLibrary(context))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("not accessible");

        verifyNoInteractions(libraryFileHelper, bookDeletionService, bookRestorationService);
    }

    @Test
    void rescanLibrary_shouldAbortWhenLibraryHasBooksButScanReturnsEmpty() throws IOException {
        givenLibrary();
        givenScan();
        when(bookRepository.countIncludingDeletedByLibraryId(LIBRARY_ID)).thenReturn(1L);

        RescanLibraryContext context = RescanLibraryContext.builder().libraryId(LIBRARY_ID).build();

        assertThatThrownBy(() -> libraryProcessingService.rescanLibrary(context))
                .isInstanceOf(APIException.class)
                .hasMessage("Library 'Test Library' was not rescanned: no book files were found in " + pathEntity.getPath()
                        + ", which may not be mounted; no books were removed.");

        verifyNoInteractions(bookDeletionService, bookRestorationService, bookGroupingService);
        // Only the read from before the walk; nothing is judged on an empty walk.
        verify(bookAdditionalFileRepository, times(1)).findScanRowsByLibraryId(LIBRARY_ID);
    }

    @Test
    void rescanLibrary_whenTheWalkTimedOutAndFoundNothing_saysWhatCouldNotBeReadAndRemovesNothing() throws IOException {
        // What a library path whose listing stalled looks like: nothing found, the path itself incomplete.
        Path root = Path.of(pathEntity.getPath());
        givenLibrary();
        givenScan(new LibraryScanResult(List.of(), Set.of(root), true, Set.of()));
        when(bookRepository.countIncludingDeletedByLibraryId(LIBRARY_ID)).thenReturn(5L);

        assertThatThrownBy(this::rescan)
                .isInstanceOf(APIException.class)
                .hasMessage("Library 'Test Library' was not rescanned: " + root
                        + " could not be read in time; no books were removed.");

        verifyNoInteractions(bookDeletionService, bookRestorationService, bookGroupingService);
        verify(bookAdditionalFileRepository, times(1)).findScanRowsByLibraryId(LIBRARY_ID);
    }

    @Test
    void rescanLibrary_whenSeveralPathsCouldNotBeReadAndNothingWasFound_namesTheFirstAndCountsTheRest() throws IOException {
        Path root = Path.of(pathEntity.getPath());
        givenLibrary();
        givenScan(new LibraryScanResult(List.of(), Set.of(root.resolve("b"), root.resolve("a"), root.resolve("c"))));
        when(bookRepository.countIncludingDeletedByLibraryId(LIBRARY_ID)).thenReturn(5L);

        // Failing outright rather than timing out, it is not said to have been slow.
        assertThatThrownBy(this::rescan)
                .isInstanceOf(APIException.class)
                .hasMessage("Library 'Test Library' was not rescanned: " + root.resolve("a")
                        + " and 2 other paths could not be read; no books were removed.");

        verifyNoInteractions(bookDeletionService, bookRestorationService, bookGroupingService);
    }

    @Test
    void rescanLibrary_whenSomePathsCouldNotBeRead_refreshesTheRestAndWarnsTheUser() throws IOException {
        Path stuck = Path.of(pathEntity.getPath(), "Author 2");
        givenLibrary();
        givenScan(new LibraryScanResult(List.of(libraryFile("Author 1", "a.epub")), Set.of(stuck), true, Set.of()));
        givenRows(row(1, 11, "Author 1", "a.epub"), row(2, 12, "Author 2", "b.epub"));
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService, never()).processDeletedLibraryFiles(any());
        assertThat(problemsNotified()).containsExactly("WARN Refreshed library 'Test Library', but " + stuck
                + " could not be read in time; no books there were removed.");
    }

    /** The library's paths are walked for real, as they are on disk. */
    private void givenRealWalk() throws IOException {
        LibraryFileHelper helper = new LibraryFileHelper(
                new LibraryDirectoryWalker(4, Duration.ofSeconds(30), new LibraryDirectoryWalker.FileAccess()));
        when(libraryFileHelper.scanAllLibraryFiles(libraryEntity)).thenAnswer(invocation -> helper.scanAllLibraryFiles(libraryEntity));
        when(libraryFileHelper.filterByAllowedFormats(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private LibraryPathEntity addLibraryPath(long id, String directory) throws IOException {
        LibraryPathEntity added = new LibraryPathEntity();
        added.setId(id);
        added.setPath(Files.createDirectory(tempDir.resolve(directory)).toString());
        List<LibraryPathEntity> paths = new ArrayList<>(libraryEntity.getLibraryPaths());
        paths.add(added);
        libraryEntity.setLibraryPaths(paths);
        return added;
    }

    @Test
    void rescanLibrary_whenTheLibraryPathIsEmpty_saysNoBookFilesWereFoundThereRatherThanThatItCouldNotBeRead() throws IOException {
        // What a share that is not mounted leaves behind: its mount point, empty.
        givenLibrary();
        givenRealWalk();
        when(bookRepository.countIncludingDeletedByLibraryId(LIBRARY_ID)).thenReturn(5L);

        assertThatThrownBy(this::rescan)
                .isInstanceOf(APIException.class)
                .hasMessage("Library 'Test Library' was not rescanned: no book files were found in " + pathEntity.getPath()
                        + ", which may not be mounted; no books were removed.");

        verifyNoInteractions(bookDeletionService, bookRestorationService, bookGroupingService);
    }

    @Test
    void rescanLibrary_ofALibraryWithNoBooksYet_doesNotWarnAboutItsEmptyPath() throws IOException {
        givenLibrary();
        givenRealWalk();
        when(bookRepository.countIncludingDeletedByLibraryId(LIBRARY_ID)).thenReturn(0L);
        givenRows();
        givenNothingToGroup();

        rescan();

        assertThat(problemsNotified()).isEmpty();
    }

    @Test
    void rescanLibrary_whenAPathThatHadBooksIsEmpty_keepsThemAndSaysItMayNotBeMounted() throws IOException {
        Files.write(Path.of(pathEntity.getPath(), "a.epub"), new byte[]{1});
        LibraryPathEntity unmounted = addLibraryPath(20L, "unmounted");
        // Empty too, but no book was ever found there, so there is nothing to warn about.
        addLibraryPath(30L, "fresh");
        givenLibrary();
        givenRealWalk();
        givenRows(row(1, 11, "", "a.epub"),
                new BookFileScanRow(2L, 12L, unmounted.getId(), unmounted.getPath(), "", "b.epub", true, BookFileType.EPUB, false));
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService, never()).processDeletedLibraryFiles(any());
        verify(bookDeletionService, never()).deleteRemovedAdditionalFiles(any());
        assertThat(problemsNotified()).containsExactly("WARN Refreshed library 'Test Library', but no book files were found in "
                + unmounted.getPath() + ", which may not be mounted; no books there were removed.");
    }

    @Test
    void rescanLibrary_shouldProceedForEmptyLibraryWithNoFilesFound() throws IOException {
        givenLibrary();
        givenScan();
        when(bookRepository.countIncludingDeletedByLibraryId(LIBRARY_ID)).thenReturn(0L);
        givenRows();
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService, never()).processDeletedLibraryFiles(any());
        assertThat(filesPassedToRescanGrouping()).isEmpty();
    }

    @Test
    void rescanLibrary_shouldProceedWhenLibraryHasBooksAndScanFindsThem() throws IOException {
        givenLibrary();
        givenScan(libraryFile("", "book1.epub"));
        givenRows(row(1, 11, "", "book1.epub"));
        givenNothingToGroup();

        rescan();

        verify(bookRepository, never()).countIncludingDeletedByLibraryId(anyLong());
        verify(bookDeletionService, never()).deleteRemovedAdditionalFiles(any());
        verify(bookDeletionService, never()).processDeletedLibraryFiles(any());
    }

    @Test
    void rescanLibrary_withNothingChanged_loadsLibraryOnceAndRowsBeforeAndAfterTheWalk() throws IOException {
        givenLibrary();
        givenScan(libraryFile("", "book (with parens).epub"));
        givenRows(row(1, 11, "", "book (with parens).epub"));
        givenNothingToGroup();

        rescan();

        verify(libraryRepository, times(1)).findById(LIBRARY_ID);
        InOrder inOrder = inOrder(bookAdditionalFileRepository, libraryFileHelper);
        inOrder.verify(bookAdditionalFileRepository).findScanRowsByLibraryId(LIBRARY_ID);
        inOrder.verify(libraryFileHelper).scanAllLibraryFiles(libraryEntity);
        inOrder.verify(bookAdditionalFileRepository).findScanRowsByLibraryId(LIBRARY_ID);
        verify(bookAdditionalFileRepository, times(2)).findScanRowsByLibraryId(LIBRARY_ID);
        assertThat(filesPassedToRescanGrouping()).isEmpty();
        verifyNoInteractions(existingBookAttacher);
    }

    @Test
    void rescanLibrary_isNotOneBigTransaction() throws NoSuchMethodException {
        // The walk and detection must not run inside a transaction; each phase opens its own.
        assertThat(LibraryProcessingService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(LibraryProcessingService.class.getMethod("rescanLibrary", RescanLibraryContext.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(LibraryProcessingService.class.getMethod("processLibrary", long.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    // ---- rescan: removed files and books ----

    @Test
    void rescanLibrary_shouldDeleteRemovedBookFormatAdditionalFiles() throws IOException {
        givenLibrary();
        // Only epub exists on disk, pdf was removed; image is not a book format
        givenScan(libraryFile("author/title", "book.epub"));
        givenRows(
                row(1, 11, "author/title", "book.epub"),
                row(2, 11, "author/title", "book.pdf", BookFileType.PDF, true, false),
                row(3, 11, "author/title", "image.png", BookFileType.PDF, false, false));
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService).deleteRemovedAdditionalFiles(List.of(2L));
        verify(bookDeletionService, never()).processDeletedLibraryFiles(any());
    }

    @Test
    void rescanLibrary_bookWhoseOnlyFileVanished_isStillTreatedAsDeleted() throws IOException {
        LibraryScanResult scan = new LibraryScanResult(List.of(libraryFile("", "kept.epub")), Set.of());
        givenLibrary();
        givenScan(scan);
        // The reads before and after the walk still have gone.epub; once it is deleted the reload
        // shows book 12 fileless.
        List<BookFileScanRow> before = List.of(row(1, 11, "", "kept.epub"), row(2, 12, "", "gone.epub"));
        when(bookAdditionalFileRepository.findScanRowsByLibraryId(LIBRARY_ID))
                .thenReturn(before, before, List.of(row(1, 11, "", "kept.epub")));
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService).deleteRemovedAdditionalFiles(List.of(2L));
        verify(bookDeletionService).processDeletedLibraryFiles(List.of(12L));
    }

    @Test
    void rescanLibrary_reloadsRowsAfterDeletionsSoLaterPhasesSeeTheCurrentState() throws IOException {
        givenLibrary();
        givenScan(libraryFile("", "kept.epub"), libraryFile("", "new.epub"));
        List<BookFileScanRow> before = List.of(row(1, 11, "", "kept.epub"), row(2, 11, "", "gone.pdf", BookFileType.PDF, true, false));
        when(bookAdditionalFileRepository.findScanRowsByLibraryId(LIBRARY_ID))
                .thenReturn(before, before, List.of(row(1, 11, "", "kept.epub")));
        givenNothingToGroup();

        rescan();

        ArgumentCaptor<LibraryBookFiles> restoreRows = ArgumentCaptor.forClass(LibraryBookFiles.class);
        verify(bookRestorationService).restoreDeletedBooks(eq(libraryEntity), restoreRows.capture(), anySet(), any());
        assertThat(restoreRows.getValue().rows()).extracting(BookFileScanRow::bookFileId).containsExactly(1L);

        ArgumentCaptor<LibraryBookFiles> purgeRows = ArgumentCaptor.forClass(LibraryBookFiles.class);
        verify(bookDeletionService).purgeDisallowedFormats(eq(libraryEntity), purgeRows.capture());
        assertThat(purgeRows.getValue()).isSameAs(restoreRows.getValue());

        verify(bookAdditionalFileRepository, times(3)).findScanRowsByLibraryId(LIBRARY_ID);
        assertThat(filesPassedToRescanGrouping()).extracting(LibraryFile::getFileName).containsExactly("new.epub");
    }

    @Test
    void rescanLibrary_reloadsRowsAfterAPurgeBeforeDetectingNewFiles() throws IOException {
        LibraryFile main = libraryFile("", "main.epub");
        LibraryFile notes = libraryFile("", "notes.pdf", BookFileType.PDF);
        LibraryFile mobi = libraryFile("", "old.mobi", BookFileType.MOBI);
        givenLibrary();
        when(libraryFileHelper.scanAllLibraryFiles(libraryEntity))
                .thenReturn(new LibraryScanResult(List.of(main, notes, mobi), Set.of()));
        when(libraryFileHelper.filterByAllowedFormats(anyList(), any())).thenReturn(List.of(main, notes));
        // Book 12 only had a now disallowed mobi plus a supplementary notes.pdf; the purge deletes
        // it, so notes.pdf is no longer known and is picked up as new.
        List<BookFileScanRow> before = List.of(
                row(1, 11, "", "main.epub"),
                row(2, 12, "", "notes.pdf", BookFileType.PDF, false, false),
                row(3, 12, "", "old.mobi", BookFileType.MOBI, true, false));
        when(bookAdditionalFileRepository.findScanRowsByLibraryId(LIBRARY_ID))
                .thenReturn(before, before, List.of(row(1, 11, "", "main.epub")));
        when(bookDeletionService.purgeDisallowedFormats(eq(libraryEntity), any())).thenReturn(true);
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService, never()).deleteRemovedAdditionalFiles(any());
        verify(bookDeletionService, never()).processDeletedLibraryFiles(any());
        verify(bookAdditionalFileRepository, times(3)).findScanRowsByLibraryId(LIBRARY_ID);
        assertThat(filesPassedToRescanGrouping()).containsExactly(notes);
    }

    @Test
    void rescanLibrary_runsThePhasesInOrder() throws IOException {
        LibraryScanResult scan = new LibraryScanResult(List.of(libraryFile("", "kept.epub")), Set.of());
        givenLibrary();
        givenScan(scan);
        givenRows(row(1, 11, "", "kept.epub"), row(2, 11, "", "gone.pdf", BookFileType.PDF, true, false),
                row(3, 12, "", "vanished.epub"));
        givenNothingToGroup();

        rescan();

        InOrder inOrder = inOrder(bookAdditionalFileRepository, libraryFileHelper, bookDeletionService,
                bookRestorationService, bookGroupingService, fileAsBookProcessor);
        inOrder.verify(bookAdditionalFileRepository).findScanRowsByLibraryId(LIBRARY_ID);
        inOrder.verify(libraryFileHelper).scanAllLibraryFiles(libraryEntity);
        inOrder.verify(bookAdditionalFileRepository).findScanRowsByLibraryId(LIBRARY_ID);
        inOrder.verify(bookDeletionService).deleteRemovedAdditionalFiles(List.of(2L, 3L));
        inOrder.verify(bookDeletionService).processDeletedLibraryFiles(List.of(12L));
        inOrder.verify(bookRestorationService).restoreDeletedBooks(eq(libraryEntity), any(), anySet(), any());
        inOrder.verify(bookDeletionService).purgeDisallowedFormats(eq(libraryEntity), any());
        inOrder.verify(bookGroupingService).groupForRescan(anyList(), eq(libraryEntity), any());
        inOrder.verify(fileAsBookProcessor).processLibraryFilesGrouped(anyMap(), eq(libraryEntity));
    }

    @Test
    void rescanLibrary_shouldNotDeleteSoftDeletedOrFilelessBooks() throws IOException {
        givenLibrary();
        givenScan(libraryFile("", "present.epub"));
        // Book 12 is already soft-deleted; fileless books have no rows at all.
        givenRows(row(1, 11, "", "present.epub"), row(2, 12, "", "gone.epub", BookFileType.EPUB, true, true));
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService, never()).processDeletedLibraryFiles(any());
        // The soft-deleted book's vanished file row is still removed, as before.
        verify(bookDeletionService).deleteRemovedAdditionalFiles(List.of(2L));
    }

    // ---- rescan: incomplete walks ----

    @Test
    void rescanLibrary_keepsBooksAndFilesUnderPathsTheScanCouldNotRead() throws IOException {
        Path unreadable = Path.of(pathEntity.getPath(), "offline");
        LibraryScanResult scan = new LibraryScanResult(List.of(libraryFile("", "present.epub")), Set.of(unreadable));
        givenLibrary();
        givenScan(scan);
        givenRows(
                row(1, 11, "", "present.epub"),
                row(2, 11, "offline/deeper", "present.pdf", BookFileType.PDF, true, false),
                row(3, 12, "offline", "unseen.epub"),
                row(4, 13, "online", "gone.epub"),
                row(5, 11, "online", "gone.mobi", BookFileType.MOBI, true, false));
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService).deleteRemovedAdditionalFiles(List.of(4L, 5L));
        verify(bookDeletionService).processDeletedLibraryFiles(List.of(13L));
    }

    @Test
    void detectionCountsWhatItKeptForAnIncompleteScan() {
        Path unreadable = Path.of(pathEntity.getPath(), "offline");
        LibraryScanResult scan = new LibraryScanResult(List.of(), Set.of(unreadable));
        LibraryBookFiles bookFiles = new LibraryBookFiles(List.of(
                row(1, 11, "offline", "a.epub"),
                row(2, 11, "offline", "a.pdf", BookFileType.PDF, true, false),
                row(3, 12, "offline/x", "b.epub"),
                row(4, 13, "", "c.epub")));
        WalkCoverage coverage = unchangedSinceWalkStart(bookFiles, scan);

        var files = libraryProcessingService.detectDeletedAdditionalFiles(Set.of(), bookFiles, coverage);
        var books = LibraryProcessingService.detectDeletedBookIds(Set.of(), bookFiles, libraryEntity, coverage);

        assertThat(files.ids()).containsExactly(4L);
        assertThat(files.underUnreadPaths()).isEqualTo(3);
        assertThat(files.changedDuringWalk()).isZero();
        assertThat(books.ids()).containsExactly(13L);
        assertThat(books.underUnreadPaths()).isEqualTo(2);
        assertThat(books.changedDuringWalk()).isZero();
    }

    @Test
    void rescanLibrary_keepsTheBooksOfADirectoryTheScanCouldNotReadWhole() throws IOException {
        // Author/Title is a folder audiobook of two tracks and 02.mp3 could not be read. The walk
        // then reports 01.mp3 as a single file, and reports the directory as incomplete since
        // how its tracks group depends on every one of them. Series holds two tracks kept as
        // separate books by a booklet that could not be read either.
        givenLibrary();
        givenScan(new LibraryScanResult(
                List.of(libraryFile("Author/Title", "01.mp3", BookFileType.AUDIOBOOK),
                        libraryFile("", "Series", BookFileType.AUDIOBOOK),
                        libraryFile("Other", "kept.epub")),
                Set.of(Path.of(pathEntity.getPath(), "Author", "Title"), Path.of(pathEntity.getPath(), "Series"))));
        givenRows(
                row(1, 11, "Author", "Title", BookFileType.AUDIOBOOK, true, false),
                row(2, 12, "Series", "a.mp3", BookFileType.AUDIOBOOK, true, false),
                row(3, 13, "Series", "b.mp3", BookFileType.AUDIOBOOK, true, false),
                row(4, 14, "Other", "kept.epub"),
                row(5, 15, "Other", "gone.epub"));
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService).deleteRemovedAdditionalFiles(List.of(5L));
        verify(bookDeletionService).processDeletedLibraryFiles(List.of(15L));
    }

    // ---- rescan: rows that change while the walk runs ----

    @Test
    void rescanLibrary_keepsABookAddedWhileTheWalkRan() throws IOException {
        // Book 12 was imported into a directory the walk had already listed: it is in the rows
        // read after the walk but was not there before it, and the walk did not see its file.
        givenLibrary();
        givenScan(libraryFile("", "a.epub"));
        when(bookAdditionalFileRepository.findScanRowsByLibraryId(LIBRARY_ID))
                .thenReturn(List.of(row(1, 11, "", "a.epub")))
                .thenReturn(List.of(row(1, 11, "", "a.epub"), row(2, 12, "Author/X", "X.epub")));
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService, never()).deleteRemovedAdditionalFiles(any());
        verify(bookDeletionService, never()).processDeletedLibraryFiles(any());
    }

    @Test
    void rescanLibrary_keepsABookMovedWhileTheWalkRanButStillDeletesOnesThatVanished() throws IOException {
        givenLibrary();
        givenScan(libraryFile("", "a.epub"));
        // Book 12's file was moved during the walk, after the walk had passed both its old and
        // its new directory. Book 13's file was gone all along.
        when(bookAdditionalFileRepository.findScanRowsByLibraryId(LIBRARY_ID))
                .thenReturn(List.of(row(1, 11, "", "a.epub"), row(2, 12, "old", "m.epub"), row(3, 13, "", "gone.epub")))
                .thenReturn(List.of(row(1, 11, "", "a.epub"), row(2, 12, "new", "m.epub"), row(3, 13, "", "gone.epub")));
        givenNothingToGroup();

        rescan();

        verify(bookDeletionService).deleteRemovedAdditionalFiles(List.of(3L));
        verify(bookDeletionService).processDeletedLibraryFiles(List.of(13L));
    }

    @Test
    void detectionCountsRowsThatChangedWhileTheWalkRan() {
        LibraryBookFiles before = new LibraryBookFiles(List.of(
                row(1, 11, "", "a.epub"),
                row(2, 12, "old", "m.epub"),
                row(3, 13, "", "gone.epub")));
        LibraryBookFiles after = new LibraryBookFiles(List.of(
                row(1, 11, "", "a.epub"),
                // moved
                row(2, 12, "new", "m.epub"),
                row(3, 13, "", "gone.epub"),
                // added, with a format that outranks book 11's epub
                row(4, 11, "", "a.pdf", BookFileType.PDF, true, false),
                // added as a new book
                row(5, 14, "", "x.epub")));
        libraryEntity.setFormatPriority(List.of(BookFileType.PDF, BookFileType.EPUB));
        WalkCoverage coverage = new WalkCoverage(Set.copyOf(before.rows()), new LibraryScanResult(List.of(), Set.of()));

        var files = libraryProcessingService.detectDeletedAdditionalFiles(Set.of(), after, coverage);
        var books = LibraryProcessingService.detectDeletedBookIds(Set.of(), after, libraryEntity, coverage);

        assertThat(files.ids()).containsExactly(1L, 3L);
        assertThat(files.changedDuringWalk()).isEqualTo(3);
        // Book 11 is judged by its new primary file, the pdf, which the walk could not judge.
        assertThat(books.ids()).containsExactly(13L);
        assertThat(books.changedDuringWalk()).isEqualTo(3);
        assertThat(books.underUnreadPaths()).isZero();
    }

    // ---- rescan: primary file selection ----

    @Test
    void detectDeletedBookIds_judgesEachBookByItsPrimaryFileUnderTheFormatPriority() {
        Set<Path> onDisk = Set.of(Path.of(pathEntity.getPath(), "a", "a.pdf"), Path.of(pathEntity.getPath(), "b", "b.epub"));
        LibraryBookFiles bookFiles = new LibraryBookFiles(List.of(
                // book 11: epub (lower id) gone, pdf present
                row(1, 11, "a", "a.epub"),
                row(2, 11, "a", "a.pdf", BookFileType.PDF, true, false),
                // book 12: epub present, pdf gone
                row(3, 12, "b", "b.epub"),
                row(4, 12, "b", "b.pdf", BookFileType.PDF, true, false)));
        WalkCoverage coverage = unchangedSinceWalkStart(bookFiles, new LibraryScanResult(List.of(), Set.of()));

        libraryEntity.setFormatPriority(List.of(BookFileType.PDF, BookFileType.EPUB));
        assertThat(LibraryProcessingService.detectDeletedBookIds(onDisk, bookFiles, libraryEntity, coverage).ids())
                .containsExactly(12L);

        libraryEntity.setFormatPriority(List.of());
        assertThat(LibraryProcessingService.detectDeletedBookIds(onDisk, bookFiles, libraryEntity, coverage).ids())
                .containsExactly(11L);
    }

    @Test
    void detectDeletedBookIds_aBookWithoutLibraryPathCountsAsGone() {
        LibraryBookFiles bookFiles = new LibraryBookFiles(List.of(
                new BookFileScanRow(1L, 11L, null, null, "", "a.epub", true, BookFileType.EPUB, false)));

        var books = LibraryProcessingService.detectDeletedBookIds(
                Set.of(Path.of(pathEntity.getPath(), "a.epub")), bookFiles, libraryEntity,
                unchangedSinceWalkStart(bookFiles, new LibraryScanResult(List.of(), Set.of())));

        assertThat(books.ids()).containsExactly(11L);
    }

    // ---- rescan: restore and new files ----

    @Test
    void rescanLibrary_restoresAReappearedBookWithoutReimportingItsFile() throws IOException {
        givenLibrary();
        givenScan(libraryFile("returned", "back.epub"), libraryFile("returned", "back.pdf"));
        // Book 12 was soft-deleted while its file was away; now back.epub is on disk again.
        givenRows(row(1, 12, "returned", "back.epub", BookFileType.EPUB, true, true));
        givenNothingToGroup();

        rescan();

        ArgumentCaptor<LibraryBookFiles> rows = ArgumentCaptor.forClass(LibraryBookFiles.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<Path>> paths = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<WalkCoverage> coverage = ArgumentCaptor.forClass(WalkCoverage.class);
        verify(bookRestorationService).restoreDeletedBooks(eq(libraryEntity), rows.capture(), paths.capture(), coverage.capture());
        assertThat(rows.getValue().rows()).extracting(BookFileScanRow::bookId).containsExactly(12L);
        assertThat(paths.getValue()).contains(Path.of(pathEntity.getPath(), "returned", "back.epub"));
        // Book 12 was already soft-deleted, at that path, before the walk started.
        assertThat(coverage.getValue().changedDuringWalk(rows.getValue().rows().getFirst())).isFalse();

        // Not deleted again, and its file is not new: only the pdf next to it goes to grouping,
        // which runs after the restore so it can attach the pdf to the restored book.
        verify(bookDeletionService, never()).deleteRemovedAdditionalFiles(any());
        verify(bookDeletionService, never()).processDeletedLibraryFiles(any());
        assertThat(filesPassedToRescanGrouping()).extracting(LibraryFile::getFileName).containsExactly("back.pdf");
        InOrder inOrder = inOrder(bookRestorationService, bookGroupingService);
        inOrder.verify(bookRestorationService).restoreDeletedBooks(any(), any(), anySet(), any());
        inOrder.verify(bookGroupingService).groupForRescan(anyList(), any(), any());
    }

    @Test
    void detectNewBookPaths_treatsEveryKnownRowAsExisting() {
        LibraryBookFiles bookFiles = new LibraryBookFiles(List.of(
                row(1, 11, "", "primary.epub"),
                row(2, 11, "", "alternate.pdf", BookFileType.PDF, true, false),
                row(3, 11, "", "notes.pdf", BookFileType.PDF, false, false),
                row(4, 12, "deleted", "old.epub", BookFileType.EPUB, true, true),
                new BookFileScanRow(5L, 13L, 99L, "/elsewhere", "", "new.epub", true, BookFileType.EPUB, false)));

        List<LibraryFile> newFiles = libraryProcessingService.detectNewBookPaths(List.of(
                libraryFile("", "primary.epub"),
                libraryFile("", "alternate.pdf"),
                libraryFile("", "notes.pdf"),
                libraryFile("deleted", "old.epub"),
                libraryFile("", "new.epub"),
                libraryFile(null, "root.epub")), bookFiles);

        // new.epub is known under another library path only; root.epub is unknown.
        assertThat(newFiles).extracting(LibraryFile::getFileName).containsExactly("new.epub", "root.epub");
    }

    // ---- rescan: auto-attach ----

    @Test
    void rescanLibrary_handsEachBooksFilesToTheAttacherWithTheRowsGroupingRead() throws IOException {
        LibraryFile pdf = libraryFile("", "book.pdf", BookFileType.PDF);
        LibraryFile shellEpub = libraryFile("Author", "Shell.epub");

        givenLibrary();
        givenScan(libraryFile("", "book.epub"), pdf, shellEpub);
        givenRows(row(1, 11, "", "book.epub"));
        when(bookGroupingService.groupForRescan(anyList(), eq(libraryEntity), any()))
                .thenReturn(new BookGroupingService.GroupingResult(List.of(
                        new BookGroupingService.Attachment(11L, ExistingBookMatcher.Kind.SAME_FOLDER, List.of(pdf)),
                        new BookGroupingService.Attachment(12L, ExistingBookMatcher.Kind.FILELESS, List.of(shellEpub))),
                        Collections.emptyMap()));

        rescan();

        ArgumentCaptor<LibraryBookFiles> rows = ArgumentCaptor.forClass(LibraryBookFiles.class);
        verify(bookGroupingService).groupForRescan(anyList(), eq(libraryEntity), rows.capture());
        assertThat(rows.getValue().rows()).extracting(BookFileScanRow::fileName).containsExactly("book.epub");
        InOrder inOrder = inOrder(existingBookAttacher, fileAsBookProcessor);
        inOrder.verify(existingBookAttacher).attach(11L, ExistingBookMatcher.Kind.SAME_FOLDER, List.of(pdf));
        inOrder.verify(existingBookAttacher).attach(12L, ExistingBookMatcher.Kind.FILELESS, List.of(shellEpub));
        inOrder.verify(fileAsBookProcessor).processLibraryFilesGrouped(anyMap(), eq(libraryEntity));
    }

    @Test
    void rescanLibrary_aFailedAttachDoesNotStopTheRest() throws IOException {
        LibraryFile missing = libraryFile("", "missing.pdf", BookFileType.PDF);
        LibraryFile ok = libraryFile("", "ok.pdf", BookFileType.PDF);

        givenLibrary();
        givenScan(missing, ok);
        givenRows();
        when(bookGroupingService.groupForRescan(anyList(), eq(libraryEntity), any()))
                .thenReturn(new BookGroupingService.GroupingResult(List.of(
                        new BookGroupingService.Attachment(11L, ExistingBookMatcher.Kind.SAME_FOLDER, List.of(missing)),
                        new BookGroupingService.Attachment(12L, ExistingBookMatcher.Kind.SAME_FOLDER, List.of(ok))),
                        Collections.emptyMap()));
        when(existingBookAttacher.attach(11L, ExistingBookMatcher.Kind.SAME_FOLDER, List.of(missing)))
                .thenThrow(new IllegalStateException("cannot read missing.pdf"));

        rescan();

        verify(existingBookAttacher).attach(12L, ExistingBookMatcher.Kind.SAME_FOLDER, List.of(ok));
        verify(fileAsBookProcessor).processLibraryFilesGrouped(anyMap(), eq(libraryEntity));
    }

    // ---- telling the user about new files left for the next scan ----

    private static ImportSummary imported(int timedOut, int notStarted, boolean storageStuck, boolean databaseErrors) {
        return new ImportSummary(10, 10 - timedOut - notStarted, 0, timedOut, notStarted, 0,
                storageStuck, databaseErrors, false, 0, Duration.ofSeconds(1), false);
    }

    private void givenAnInitialScanImporting(ImportSummary summary) throws IOException {
        givenLibrary();
        givenRows();
        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(libraryFile("", "book1.epub")));
        givenInitialScanGroupsPerFile();
        when(fileAsBookProcessor.processLibraryFilesGrouped(anyMap(), eq(libraryEntity))).thenReturn(summary);
    }

    /** The warnings and errors sent; the started and finished messages are left out. */
    private List<String> problemsNotified() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(notificationService, atLeastOnce()).sendMessage(eq(Topic.LOG), captor.capture());
        return captor.getAllValues().stream()
                .map(LogNotification.class::cast)
                .filter(n -> n.getSeverity() != Severity.INFO)
                .map(n -> n.getSeverity() + " " + n.getMessage())
                .toList();
    }

    @Test
    void processLibrary_whenFilesTimedOut_warnsThatTheyWillBeRetried() throws IOException {
        givenAnInitialScanImporting(imported(2, 1, false, false));

        libraryProcessingService.processLibrary(LIBRARY_ID);

        assertThat(problemsNotified()).containsExactly("WARN 3 new files in library 'Test Library' were not added because "
                + "storage did not respond in time; they will be retried on the next scan.");
    }

    @Test
    void rescanLibrary_whenStorageStoppedResponding_reportsAnError() throws IOException {
        givenLibrary();
        givenScan(libraryFile("", "book1.epub"));
        givenRows();
        when(bookGroupingService.groupForRescan(anyList(), eq(libraryEntity), any()))
                .thenReturn(new BookGroupingService.GroupingResult(List.of(), Map.of("book1", List.of(libraryFile("", "book1.epub")))));
        when(fileAsBookProcessor.processLibraryFilesGrouped(anyMap(), eq(libraryEntity))).thenReturn(imported(1, 6, true, false));

        rescan();

        assertThat(problemsNotified()).containsExactly("ERROR Storage for library 'Test Library' stopped responding; 7 new files "
                + "were not added. They will be retried on the next scan. Check the share, or restart Booklore if it stays stuck.");
    }

    @Test
    void processLibrary_whenSavingKeptFailing_reportsAnErrorThatDoesNotBlameStorage() throws IOException {
        givenAnInitialScanImporting(imported(0, 5, false, true));

        libraryProcessingService.processLibrary(LIBRARY_ID);

        assertThat(problemsNotified()).containsExactly("ERROR Saving new books in library 'Test Library' kept failing, so the "
                + "import stopped; 5 new files were not added. They will be retried on the next scan.");
    }

    @Test
    void processLibrary_whenEverythingWasAdded_reportsNothing() throws IOException {
        givenAnInitialScanImporting(imported(0, 0, false, false));

        libraryProcessingService.processLibrary(LIBRARY_ID);

        assertThat(problemsNotified()).isEmpty();
    }

    @Test
    void processLibrary_withoutASummaryOrWithTheSequentialImport_reportsNothing() throws IOException {
        givenAnInitialScanImporting(null);
        libraryProcessingService.processLibrary(LIBRARY_ID);
        assertThat(problemsNotified()).isEmpty();

        clearInvocations(notificationService);
        when(fileAsBookProcessor.processLibraryFilesGrouped(anyMap(), eq(libraryEntity)))
                .thenReturn(ImportSummary.legacy(3, Duration.ofSeconds(1)));
        libraryProcessingService.processLibrary(LIBRARY_ID);
        assertThat(problemsNotified()).isEmpty();
    }
}
