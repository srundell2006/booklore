package org.booklore.service.file;

import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileMoveHelper Tests")
class FileMoveHelperTest {

    @Mock
    private MonitoringRegistrationService monitoringRegistrationService;

    @Mock
    private AppSettingService appSettingService;

    private FileMoveHelper fileMoveHelper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fileMoveHelper = new FileMoveHelper(monitoringRegistrationService, appSettingService);
    }

    /** Non-ASCII file names need the JVM to encode them as UTF-8, as it does under a UTF-8 locale (LANG=C.UTF-8). */
    private static void assumeUnicodeFileNames() {
        assumeTrue(Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8")).newEncoder().canEncode("한강"),
                "File names are not encoded as UTF-8 here: run under a UTF-8 locale");
    }

    @Nested
    @DisplayName("SMB Share Behavior Tests")
    class SmbShareBehaviorTests {

        @Test
        @DisplayName("waitForFileAccessible retries when file appears after delay (simulates SMB latency)")
        void waitForFileAccessible_fileAppearsAfterDelay_eventuallySucceeds() throws Exception {
            Path filePath = tempDir.resolve("delayed-file.txt");

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            try {
                executor.schedule(() -> {
                    try {
                        Files.writeString(filePath, "content");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, 150, TimeUnit.MILLISECONDS);

                boolean result = fileMoveHelper.waitForFileAccessible(filePath);

                assertTrue(result, "File should become accessible after retries");
                assertTrue(Files.exists(filePath));
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("waitForFileAccessible returns false when file never appears (simulates SMB file not found)")
        void waitForFileAccessible_fileNeverAppears_returnsFalse() {
            Path nonExistentFile = tempDir.resolve("does-not-exist.txt");

            long startTime = System.currentTimeMillis();
            boolean result = fileMoveHelper.waitForFileAccessible(nonExistentFile);
            long elapsed = System.currentTimeMillis() - startTime;

            assertFalse(result);
            assertTrue(elapsed >= 200, "Should have waited for at least 2 retry delays (100ms each)");
        }

        @Test
        @DisplayName("moveFile throws NoSuchFileException when source never becomes accessible")
        void moveFile_sourceNeverAccessible_throwsNoSuchFileException() {
            Path source = tempDir.resolve("missing-source.txt");
            Path target = tempDir.resolve("target.txt");

            NoSuchFileException exception = assertThrows(NoSuchFileException.class,
                    () -> fileMoveHelper.moveFile(source, target));

            assertTrue(exception.getMessage().contains("Source file not accessible after retries"));
        }

        @Test
        @DisplayName("moveFile succeeds when source appears during retry (simulates SMB eventual consistency)")
        void moveFile_sourceAppearsAfterRetry_succeeds() throws Exception {
            Path source = tempDir.resolve("eventual-source.txt");
            Path target = tempDir.resolve("subdir/target.txt");

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            try {
                executor.schedule(() -> {
                    try {
                        Files.writeString(source, "delayed content");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, 50, TimeUnit.MILLISECONDS);

                fileMoveHelper.moveFile(source, target);

                assertTrue(Files.exists(target));
                assertFalse(Files.exists(source));
                assertEquals("delayed content", Files.readString(target));
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("moveFileWithBackup creates temp file and returns its path")
        void moveFileWithBackup_createsTemporaryFile() throws Exception {
            Path source = tempDir.resolve("original.txt");
            Files.writeString(source, "original content");

            Path tempPath = fileMoveHelper.moveFileWithBackup(source);

            assertFalse(Files.exists(source), "Original should be moved");
            assertTrue(Files.exists(tempPath), "Temp file should exist");
            assertTrue(tempPath.getFileName().toString().endsWith(".tmp_move"));
            assertEquals("original content", Files.readString(tempPath));
        }

        @Test
        @DisplayName("commitMove moves temp file to final destination")
        void commitMove_movesToFinalDestination() throws Exception {
            Path tempPath = tempDir.resolve("file.txt.tmp_move");
            Path target = tempDir.resolve("subdir/final.txt");
            Files.writeString(tempPath, "content");

            fileMoveHelper.commitMove(tempPath, target);

            assertFalse(Files.exists(tempPath), "Temp file should be removed");
            assertTrue(Files.exists(target), "Target should exist");
            assertEquals("content", Files.readString(target));
        }

        @Test
        @DisplayName("rollbackMove restores original file from temp location")
        void rollbackMove_restoresOriginalFile() throws Exception {
            Path tempPath = tempDir.resolve("file.txt.tmp_move");
            Path originalSource = tempDir.resolve("original.txt");
            Files.writeString(tempPath, "content");

            fileMoveHelper.rollbackMove(tempPath, originalSource);

            assertFalse(Files.exists(tempPath), "Temp file should be removed");
            assertTrue(Files.exists(originalSource), "Original should be restored");
            assertEquals("content", Files.readString(originalSource));
        }

        @Test
        @DisplayName("rollbackMove does nothing when temp file doesn't exist")
        void rollbackMove_noTempFile_doesNothing() {
            Path tempPath = tempDir.resolve("nonexistent.tmp_move");
            Path originalSource = tempDir.resolve("original.txt");

            assertDoesNotThrow(() -> fileMoveHelper.rollbackMove(tempPath, originalSource));
            assertFalse(Files.exists(originalSource));
        }
    }

    @Nested
    @DisplayName("Target Conflict Tests")
    class TargetConflictTests {

        @Test
        @DisplayName("commitMove refuses to replace an existing file, without retrying")
        void commitMove_existingTarget_throwsAndLeavesBothFiles() throws Exception {
            Path tempPath = tempDir.resolve("book.epub.tmp_move");
            Path target = tempDir.resolve("Title.epub");
            Files.writeString(tempPath, "moving book");
            Files.writeString(target, "another book");

            long startTime = System.currentTimeMillis();
            assertThrows(FileAlreadyExistsException.class, () -> fileMoveHelper.commitMove(tempPath, target));
            long elapsed = System.currentTimeMillis() - startTime;

            assertEquals("moving book", Files.readString(tempPath));
            assertEquals("another book", Files.readString(target));
            assertTrue(elapsed < 100, "Should fail at once instead of waiting to retry");
        }

        @Test
        @DisplayName("commitMove refuses to replace an existing folder, even an empty one")
        void commitMove_existingTargetFolder_throws() throws Exception {
            Path tempPath = Files.createDirectories(tempDir.resolve("01. Audiobook (2001).tmp_move"));
            Files.writeString(tempPath.resolve("part1.mp3"), "audio");
            Path target = Files.createDirectories(tempDir.resolve("Title"));

            assertThrows(FileAlreadyExistsException.class, () -> fileMoveHelper.commitMove(tempPath, target));

            assertTrue(Files.exists(tempPath.resolve("part1.mp3")));
            assertTrue(Files.isDirectory(target));
        }

        @Test
        @DisplayName("commitMove refuses a target that is another link to the same file")
        void commitMove_targetLinkedToTheSameFile_throws() throws Exception {
            Path tempPath = tempDir.resolve("book.epub.tmp_move");
            Files.writeString(tempPath, "the book");
            Path target = Files.createLink(tempDir.resolve("Title.epub"), tempPath);

            assertThrows(FileAlreadyExistsException.class, () -> fileMoveHelper.commitMove(tempPath, target));

            assertTrue(Files.exists(tempPath));
        }

        @Test
        @DisplayName("isTakenByAnotherFile: a free target, or the source itself, is not taken")
        void isTakenByAnotherFile_freeOrSourceItself_isFalse() throws Exception {
            Path source = tempDir.resolve("the hobbit.epub");
            Files.writeString(source, "the book");
            // A second name for the same file, as a case-insensitive share finds "The Hobbit.epub"
            Path sameFile = Files.createLink(tempDir.resolve("The Hobbit.epub"), source);

            assertFalse(fileMoveHelper.isTakenByAnotherFile(tempDir.resolve("Free.epub"), source));
            assertFalse(fileMoveHelper.isTakenByAnotherFile(source, source));
            assertFalse(fileMoveHelper.isTakenByAnotherFile(sameFile, source));
        }

        @Test
        @DisplayName("isTakenByAnotherFile: another file or folder at the target takes it")
        void isTakenByAnotherFile_anotherFileOrFolder_isTrue() throws Exception {
            Path source = tempDir.resolve("book.epub");
            Path folderSource = Files.createDirectories(tempDir.resolve("01. Audiobook"));
            Files.writeString(source, "the book");
            Path otherFile = Files.writeString(tempDir.resolve("Title.epub"), "another book");
            Path otherFolder = Files.createDirectories(tempDir.resolve("Title"));

            assertTrue(fileMoveHelper.isTakenByAnotherFile(otherFile, source));
            assertTrue(fileMoveHelper.isTakenByAnotherFile(otherFolder, folderSource));
        }

        @Test
        @DisplayName("isSamePathIgnoringNormalization: composed and decomposed spellings are the same path")
        void isSamePathIgnoringNormalization_comparesComposedForms() throws Exception {
            // A zip file system holds any name, where the default one cannot under a non-UTF-8 locale
            try (FileSystem names = FileSystems.newFileSystem(tempDir.resolve("names.zip"), Map.of("create", "true"))) {
                String composed = "/books/한강/소년이 온다.epub";
                Path decomposed = names.getPath(Normalizer.normalize(composed, Normalizer.Form.NFD));

                assertNotEquals(names.getPath(composed), decomposed);
                assertTrue(FileMoveHelper.isSamePathIgnoringNormalization(names.getPath(composed), decomposed));
                assertFalse(FileMoveHelper.isSamePathIgnoringNormalization(names.getPath(composed), names.getPath("/books/한강/채식주의자.epub")));
                assertFalse(FileMoveHelper.isSamePathIgnoringNormalization(names.getPath("/books/a.epub"), names.getPath("/books/A.epub")));
            }
        }

        @Test
        @DisplayName("isAlreadyInPlace: a decomposed name is in place only in the directory the book goes to")
        void isAlreadyInPlace_decomposedNameInTheTargetDirectoryOnly() throws Exception {
            assumeUnicodeFileNames();
            String composedAuthor = "한강";
            String decomposedAuthor = Normalizer.normalize(composedAuthor, Normalizer.Form.NFD);
            Path decomposedDir = Files.createDirectories(tempDir.resolve(decomposedAuthor));
            Path source = Files.writeString(decomposedDir.resolve("notes.txt"), "notes");
            Path target = tempDir.resolve(composedAuthor).resolve("notes.txt");

            assertTrue(FileMoveHelper.isAlreadyInPlace(source, source));
            // A sensitive file system: the composed directory is another one, and the book moves there
            assertFalse(FileMoveHelper.isAlreadyInPlace(source, target));
            // A share that finds the decomposed directory under the composed name, as a link does here
            Files.createSymbolicLink(tempDir.resolve(composedAuthor), decomposedDir);
            assertTrue(FileMoveHelper.isAlreadyInPlace(source, target));
            assertFalse(FileMoveHelper.isAlreadyInPlace(source, tempDir.resolve(composedAuthor).resolve("other.txt")));
        }

        @Test
        @DisplayName("isAbsent: only a missing path is absent, a dangling link is not")
        void isAbsent_onlyAMissingPath() throws Exception {
            Path file = Files.writeString(tempDir.resolve("book.epub"), "the book");
            Path link = Files.createSymbolicLink(tempDir.resolve("link.epub"), tempDir.resolve("gone.epub"));

            assertTrue(FileMoveHelper.isAbsent(tempDir.resolve("missing.epub")));
            assertFalse(FileMoveHelper.isAbsent(file));
            assertFalse(FileMoveHelper.isAbsent(tempDir));
            assertFalse(FileMoveHelper.isAbsent(link));
        }

        @Test
        @DisplayName("A target that cannot be read is never taken to be free")
        void unreadableTarget_isNotFree() throws Exception {
            // Reading a path through a link loop fails, as reading one on a share that drops out fails with an I/O error
            Path loop = Files.createSymbolicLink(tempDir.resolve("Author"), tempDir.resolve("Author"));
            Path source = Files.writeString(tempDir.resolve("book.epub"), "the book");
            Path unreadable = loop.resolve("Title.epub");

            assertFalse(Files.exists(unreadable), "Files.exists takes it to be free");
            assertThrows(FileSystemException.class, () -> FileMoveHelper.isAbsent(unreadable));
            assertThrows(FileSystemException.class, () -> fileMoveHelper.isTakenByAnotherFile(unreadable, source));
        }

        @Test
        @DisplayName("rollbackMove never replaces a file that has taken the source's place")
        void rollbackMove_sourceTaken_leavesBothFiles() throws Exception {
            Path tempPath = Files.writeString(tempDir.resolve("Title.epub.tmp_move"), "moving book");
            Path originalSource = Files.writeString(tempDir.resolve("Title.epub"), "another book");

            assertDoesNotThrow(() -> fileMoveHelper.rollbackMove(tempPath, originalSource));

            assertEquals("moving book", Files.readString(tempPath));
            assertEquals("another book", Files.readString(originalSource));
        }

        @Test
        @DisplayName("rollbackMove recreates a source directory removed since the move")
        void rollbackMove_sourceDirectoryGone_isRecreated() throws Exception {
            Path moved = Files.writeString(tempDir.resolve("Title.epub"), "the book");
            Path originalSource = tempDir.resolve("incoming/old/book.epub");

            fileMoveHelper.rollbackMove(moved, originalSource);

            assertFalse(Files.exists(moved));
            assertEquals("the book", Files.readString(originalSource));
        }

        @Test
        @DisplayName("moveFileWithBackup never replaces a temporary file left behind by a failed rollback")
        void moveFileWithBackup_leftoverTempFile_throws() throws Exception {
            Path source = Files.writeString(tempDir.resolve("Title.epub"), "the book");
            Path leftover = Files.writeString(tempDir.resolve("Title.epub.tmp_move"), "another book");

            assertThrows(FileAlreadyExistsException.class, () -> fileMoveHelper.moveFileWithBackup(source));

            assertEquals("the book", Files.readString(source));
            assertEquals("another book", Files.readString(leftover));
        }

        @Test
        @DisplayName("A name too long to take .tmp_move within 255 bytes still moves, by way of a shorter temporary name")
        void moveFileWithBackup_nameTooLongForTheSuffix_movesByAShorterName() throws Exception {
            assumeUnicodeFileNames();
            // 249 bytes, Arabic letters taking two each: 258 with ".tmp_move"
            String name = "ك".repeat(122) + ".epub";
            assertEquals(249, name.getBytes(StandardCharsets.UTF_8).length);
            Path source = Files.writeString(tempDir.resolve(name), "the book");
            Path target = tempDir.resolve("Title.epub");

            Path tempPath = fileMoveHelper.moveFileWithBackup(source);
            fileMoveHelper.commitMove(tempPath, target);

            assertTrue(tempPath.getFileName().toString().endsWith(".tmp_move"));
            assertTrue(tempPath.getFileName().toString().getBytes(StandardCharsets.UTF_8).length <= 255);
            assertEquals("the book", Files.readString(target));
            assertFalse(Files.exists(source));
        }

        @Test
        @DisplayName("Long names that differ only at the end get different temporary names")
        void temporaryPath_longNamesDifferingAtTheEnd_differ() {
            String name = "a".repeat(244);

            Path first = FileMoveHelper.temporaryPath(tempDir.resolve(name + ".epub"));
            Path second = FileMoveHelper.temporaryPath(tempDir.resolve(name + " (1).epub"));

            assertNotEquals(first, second);
            assertEquals(255, first.getFileName().toString().length());
            assertEquals(tempDir.resolve("Title.epub.tmp_move"), FileMoveHelper.temporaryPath(tempDir.resolve("Title.epub")));
        }
    }

    @Nested
    @DisplayName("Lost Reply Tests")
    class LostReplyTests {

        /** A share that renames on the server but loses the reply the first time, as a reconnecting SMB client can. */
        private FileMoveHelper helperLosingTheFirstReply() {
            return new FileMoveHelper(monitoringRegistrationService, appSettingService) {
                private boolean replyLost;

                @Override
                protected void rename(Path source, Path target) throws IOException {
                    super.rename(source, target);
                    if (!replyLost) {
                        replyLost = true;
                        throw new FileSystemException(source.toString(), target.toString(), "Connection reset");
                    }
                }
            };
        }

        @Test
        @DisplayName("A staging rename that took effect although it failed counts as done, so it can be rolled back")
        void moveFileWithBackup_renameDoneButReplyLost_returnsTheTemporaryPath() throws Exception {
            Path source = Files.writeString(tempDir.resolve("book.epub"), "the book");

            Path tempPath = helperLosingTheFirstReply().moveFileWithBackup(source);

            assertEquals("the book", Files.readString(tempPath));
            fileMoveHelper.rollbackMove(tempPath, source);
            assertEquals("the book", Files.readString(source));
        }

        @Test
        @DisplayName("A commit that took effect although it failed counts as done, instead of failing on the moved file")
        void commitMove_renameDoneButReplyLost_succeeds() throws Exception {
            Path tempPath = Files.writeString(tempDir.resolve("book.epub.tmp_move"), "the book");
            Path target = tempDir.resolve("Title.epub");

            assertDoesNotThrow(() -> helperLosingTheFirstReply().commitMove(tempPath, target));

            assertEquals("the book", Files.readString(target));
            assertFalse(Files.exists(tempPath));
        }

        @Test
        @DisplayName("A rename that failed without taking effect is still retried")
        void commitMove_renameFailedOnce_isRetried() throws Exception {
            Path tempPath = Files.writeString(tempDir.resolve("book.epub.tmp_move"), "the book");
            Path target = tempDir.resolve("Title.epub");
            FileMoveHelper failingOnce = new FileMoveHelper(monitoringRegistrationService, appSettingService) {
                private boolean failed;

                @Override
                protected void rename(Path source, Path target) throws IOException {
                    if (!failed) {
                        failed = true;
                        throw new FileSystemException(source.toString(), target.toString(), "Connection reset");
                    }
                    super.rename(source, target);
                }
            };

            failingOnce.commitMove(tempPath, target);

            assertEquals("the book", Files.readString(target));
        }
    }

    @Nested
    @DisplayName("Retry Mechanism Tests")
    class RetryMechanismTests {

        @Test
        @DisplayName("waitForFileAccessible succeeds immediately when file exists")
        void waitForFileAccessible_fileExists_succeedsImmediately() throws Exception {
            Path existingFile = tempDir.resolve("existing.txt");
            Files.writeString(existingFile, "content");

            long startTime = System.currentTimeMillis();
            boolean result = fileMoveHelper.waitForFileAccessible(existingFile);
            long elapsed = System.currentTimeMillis() - startTime;

            assertTrue(result);
            assertTrue(elapsed < 50, "Should return immediately without delays");
        }
    }

    @Nested
    @DisplayName("Directory Cleanup Tests")
    class DirectoryCleanupTests {

        @Test
        @DisplayName("Deletes empty parent directories up to library root")
        void deleteEmptyParentDirs_deletesEmptyDirectories() throws Exception {
            Path libraryRoot = tempDir.resolve("library");
            Path nestedDir = libraryRoot.resolve("author/series/book");
            Files.createDirectories(nestedDir);

            fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(nestedDir, Set.of(libraryRoot));

            assertFalse(Files.exists(nestedDir));
            assertFalse(Files.exists(libraryRoot.resolve("author/series")));
            assertFalse(Files.exists(libraryRoot.resolve("author")));
            assertTrue(Files.exists(libraryRoot), "Library root should not be deleted");
        }

        @Test
        @DisplayName("Stops at directory containing non-ignored files")
        void deleteEmptyParentDirs_stopsAtNonEmptyDirectory() throws Exception {
            Path libraryRoot = tempDir.resolve("library");
            Path authorDir = libraryRoot.resolve("author");
            Path seriesDir = authorDir.resolve("series");
            Path bookDir = seriesDir.resolve("book");
            Files.createDirectories(bookDir);
            Files.writeString(authorDir.resolve("other-file.txt"), "content");

            fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(bookDir, Set.of(libraryRoot));

            assertFalse(Files.exists(bookDir));
            assertFalse(Files.exists(seriesDir));
            assertTrue(Files.exists(authorDir), "Should stop at directory with other files");
            assertTrue(Files.exists(authorDir.resolve("other-file.txt")));
        }

        @Test
        @DisplayName("Deletes directories containing only ignored files (.DS_Store, Thumbs.db)")
        void deleteEmptyParentDirs_handlesMultipleIgnoredFiles() throws Exception {
            Path libraryRoot = tempDir.resolve("library");
            Path nestedDir = libraryRoot.resolve("author");
            Files.createDirectories(nestedDir);
            Files.writeString(nestedDir.resolve(".DS_Store"), "mac metadata");
            Files.writeString(nestedDir.resolve("Thumbs.db"), "windows thumbnail cache");

            fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(nestedDir, Set.of(libraryRoot));

            assertFalse(Files.exists(nestedDir));
            assertTrue(Files.exists(libraryRoot));
        }

        @Test
        @DisplayName("Does not delete library root even if empty")
        void deleteEmptyParentDirs_neverDeletesLibraryRoot() throws Exception {
            Path libraryRoot = tempDir.resolve("library");
            Files.createDirectories(libraryRoot);

            fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(libraryRoot, Set.of(libraryRoot));

            assertTrue(Files.exists(libraryRoot), "Library root should never be deleted");
        }

        @Test
        @DisplayName("Recursively deletes nested empty subdirectories")
        void deleteEmptyParentDirs_deletesNestedEmptySubdirectories() throws Exception {
            Path libraryRoot = tempDir.resolve("library");
            Path authorDir = libraryRoot.resolve("author");
            Path seriesDir = authorDir.resolve("series");
            Path deepEmptyDir = seriesDir.resolve("volume/chapter");
            Files.createDirectories(deepEmptyDir);

            fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(authorDir, Set.of(libraryRoot));

            assertFalse(Files.exists(deepEmptyDir), "Deep empty directory should be deleted");
            assertFalse(Files.exists(seriesDir), "Series directory should be deleted");
            assertFalse(Files.exists(authorDir), "Author directory should be deleted");
            assertTrue(Files.exists(libraryRoot), "Library root should remain");
        }

        @Test
        @DisplayName("Does not delete subdirectories containing real files")
        void deleteEmptyParentDirs_preservesSubdirectoriesWithFiles() throws Exception {
            Path libraryRoot = tempDir.resolve("library");
            Path authorDir = libraryRoot.resolve("author");
            Path seriesWithBook = authorDir.resolve("series-with-book");
            Path emptySeriesDir = authorDir.resolve("empty-series");
            Files.createDirectories(seriesWithBook);
            Files.createDirectories(emptySeriesDir);
            Files.writeString(seriesWithBook.resolve("book.epub"), "ebook content");

            fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(authorDir, Set.of(libraryRoot));

            assertFalse(Files.exists(emptySeriesDir), "Empty subdirectory should be deleted");
            assertTrue(Files.exists(seriesWithBook), "Subdirectory with files should remain");
            assertTrue(Files.exists(seriesWithBook.resolve("book.epub")), "File should remain");
            assertTrue(Files.exists(authorDir), "Parent should remain because it has non-empty subdirectory");
        }

        @Test
        @DisplayName("Deletes subdirectories containing only ignored files")
        void deleteEmptyParentDirs_deletesSubdirectoriesWithOnlyIgnoredFiles() throws Exception {
            Path libraryRoot = tempDir.resolve("library");
            Path authorDir = libraryRoot.resolve("author");
            Path subDirWithIgnored = authorDir.resolve("series");
            Files.createDirectories(subDirWithIgnored);
            Files.writeString(subDirWithIgnored.resolve(".DS_Store"), "mac metadata");
            Files.writeString(subDirWithIgnored.resolve("Thumbs.db"), "windows cache");

            fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(authorDir, Set.of(libraryRoot));

            assertFalse(Files.exists(subDirWithIgnored), "Subdirectory with only ignored files should be deleted");
            assertFalse(Files.exists(authorDir), "Parent should be deleted");
            assertTrue(Files.exists(libraryRoot), "Library root should remain");
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("moveFile creates target directory if it doesn't exist")
        void moveFile_createsTargetDirectory() throws Exception {
            Path source = tempDir.resolve("source.txt");
            Path target = tempDir.resolve("deep/nested/directory/target.txt");
            Files.writeString(source, "content");

            fileMoveHelper.moveFile(source, target);

            assertTrue(Files.exists(target));
            assertTrue(Files.isDirectory(target.getParent()));
        }

        @Test
        @DisplayName("moveFile handles file with special characters in name")
        void moveFile_specialCharactersInName() throws Exception {
            Path source = tempDir.resolve("file with spaces & special (chars).txt");
            Path target = tempDir.resolve("renamed [file].txt");
            Files.writeString(source, "content");

            fileMoveHelper.moveFile(source, target);

            assertTrue(Files.exists(target));
            assertEquals("content", Files.readString(target));
        }

        @Test
        @DisplayName("deleteEmptyParentDirs handles symlinks safely")
        void deleteEmptyParentDirs_handlesSymlinks() throws Exception {
            Path libraryRoot = tempDir.resolve("library");
            Path realDir = tempDir.resolve("realdir");
            Path nestedDir = libraryRoot.resolve("nested");
            Files.createDirectories(realDir);
            Files.createDirectories(nestedDir);

            try {
                Path symlink = nestedDir.resolve("symlink");
                Files.createSymbolicLink(symlink, realDir);

                fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(nestedDir, Set.of(libraryRoot));

                assertTrue(Files.exists(nestedDir), "Should not delete dir with symlink");
                assertTrue(Files.exists(realDir), "Real directory should still exist");
            } catch (UnsupportedOperationException e) {
                // Symlinks not supported on this system, skip test
            }
        }
    }
}
