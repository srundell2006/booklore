package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.service.library.LibraryDirectoryWalker.Directory;
import org.booklore.service.library.LibraryDirectoryWalker.WalkedPath;
import org.booklore.util.FileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds a library's book files. {@link LibraryDirectoryWalker} lists the library's paths
 * (in parallel, leaving out ignored entries); this class then visits the resulting tree
 * the way the old {@code Files.walkFileTree} visitor did and applies the same grouping
 * rules. Within a library path, directories are visited in name order, a directory's own
 * files before its subdirectories, so the output order is the same from scan to scan.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryFileHelper {

    static final int MIN_AUDIO_FILES_FOR_FOLDER_AUDIOBOOK = 2;

    private final LibraryDirectoryWalker directoryWalker;

    /**
     * Walks every path of the library (auto-detect grouping, all formats) and reports both
     * the files found and any paths the walk could not read.
     */
    public LibraryScanResult scanAllLibraryFiles(LibraryEntity libraryEntity) throws IOException {
        return walk(libraryEntity, this::findLibraryFiles);
    }

    public List<LibraryFile> getAllLibraryFiles(LibraryEntity libraryEntity) throws IOException {
        return walk(libraryEntity, this::findLibraryFiles).files();
    }

    public List<LibraryFile> getLibraryFiles(LibraryEntity libraryEntity) throws IOException {
        LibraryOrganizationMode mode = libraryEntity.getOrganizationMode() != null
                ? libraryEntity.getOrganizationMode() : LibraryOrganizationMode.AUTO_DETECT;

        List<LibraryFile> allFiles = switch (mode) {
            case BOOK_PER_FILE, BOOK_PER_FOLDER -> getAllLibraryFilesFlat(libraryEntity);
            case AUTO_DETECT -> getAllLibraryFiles(libraryEntity);
        };
        return filterByAllowedFormats(allFiles, libraryEntity.getAllowedFormats());
    }

    public List<LibraryFile> getAllLibraryFilesFlat(LibraryEntity libraryEntity) throws IOException {
        return walk(libraryEntity, this::findLibraryFilesFlat).files();
    }

    /** Builds the library files of one walked library path. */
    @FunctionalInterface
    private interface TreeCollector {
        List<LibraryFile> collect(LibraryPathEntity pathEntity, LibraryEntity libraryEntity, Directory root);
    }

    /** The callbacks of a visit over a walked tree, as in {@link java.nio.file.FileVisitor}. */
    @FunctionalInterface
    private interface TreeVisitor {
        void visitFile(Path file);

        default void postVisitDirectory(Path dir) {
        }
    }

    /**
     * Walks all of the library's paths together, then collects each path's files in turn,
     * along with whatever the walk could not read.
     */
    private LibraryScanResult walk(LibraryEntity libraryEntity, TreeCollector collector) throws IOException {
        List<LibraryPathEntity> pathEntities = libraryEntity.getLibraryPaths();
        List<WalkedPath> walkedPaths = directoryWalker.walk(pathEntities.stream()
                .map(pathEntity -> Path.of(pathEntity.getPath()))
                .toList());

        List<LibraryFile> allFiles = new ArrayList<>();
        Set<Path> incompletePaths = new HashSet<>();
        Set<Path> emptyLibraryPaths = new HashSet<>();
        boolean timedOut = false;
        for (int i = 0; i < pathEntities.size(); i++) {
            WalkedPath walked = walkedPaths.get(i);
            incompletePaths.addAll(walked.incompletePaths());
            timedOut |= walked.timedOut();
            if (walked.empty()) {
                emptyLibraryPaths.add(walked.path());
            }
            if (walked.root() != null) {
                allFiles.addAll(collector.collect(pathEntities.get(i), libraryEntity, walked.root()));
            }
        }
        return new LibraryScanResult(allFiles, incompletePaths, timedOut, emptyLibraryPaths);
    }

    /**
     * Calls {@code visitor} in the order Files.walkFileTree would over this tree: each book
     * file of a directory, then each subdirectory in full, then the directory itself. The
     * walker has already dropped everything the old visitor skipped.
     */
    private static void visit(Directory directory, TreeVisitor visitor) {
        for (String fileName : directory.getBookFiles()) {
            visitor.visitFile(directory.getPath().resolve(fileName));
        }
        for (Directory subdirectory : directory.getSubdirectories()) {
            visit(subdirectory, visitor);
        }
        visitor.postVisitDirectory(directory.getPath());
    }

    private List<LibraryFile> findLibraryFilesFlat(LibraryPathEntity pathEntity, LibraryEntity libraryEntity, Directory root) {
        Path libraryPath = Path.of(pathEntity.getPath());
        List<LibraryFile> libraryFiles = new ArrayList<>();
        Map<Path, List<Path>> dirAudioFiles = new LinkedHashMap<>();

        visit(root, file -> {
            String fileName = file.getFileName().toString();
            BookFileType fileType = bookFileType(fileName);

            if (fileType == BookFileType.AUDIOBOOK) {
                dirAudioFiles.computeIfAbsent(file.getParent(), k -> new ArrayList<>()).add(file);
            } else {
                libraryFiles.add(LibraryFile.builder()
                        .libraryEntity(libraryEntity)
                        .libraryPathEntity(pathEntity)
                        .fileSubPath(FileUtils.getRelativeSubPath(pathEntity.getPath(), file))
                        .fileName(fileName)
                        .bookFileType(fileType)
                        .build());
            }
        });

        // Collapse audio files: 2+ audio files in same directory become a single folder-based audiobook.
        // A single audio file (e.g. standalone m4b) stays as an individual file entry.
        // Audio files at the library root are always added individually (no folder to name the audiobook after).
        for (var entry : dirAudioFiles.entrySet()) {
            Path dir = entry.getKey();
            List<Path> audioFiles = entry.getValue();

            if (dir.equals(libraryPath) || audioFiles.size() < MIN_AUDIO_FILES_FOR_FOLDER_AUDIOBOOK) {
                for (Path audioFile : audioFiles) {
                    libraryFiles.add(LibraryFile.builder()
                            .libraryEntity(libraryEntity)
                            .libraryPathEntity(pathEntity)
                            .fileSubPath(FileUtils.getRelativeSubPath(pathEntity.getPath(), audioFile))
                            .fileName(audioFile.getFileName().toString())
                            .bookFileType(BookFileType.AUDIOBOOK)
                            .build());
                }
            } else {
                libraryFiles.add(LibraryFile.builder()
                        .libraryEntity(libraryEntity)
                        .libraryPathEntity(pathEntity)
                        .fileSubPath(FileUtils.getRelativeSubPath(pathEntity.getPath(), dir))
                        .fileName(dir.getFileName().toString())
                        .bookFileType(BookFileType.AUDIOBOOK)
                        .folderBased(true)
                        .build());
            }
        }

        return libraryFiles;
    }

    List<LibraryFile> filterByAllowedFormats(List<LibraryFile> files, List<BookFileType> allowedFormats) {
        if (allowedFormats == null || allowedFormats.isEmpty()) {
            return files;
        }
        Set<BookFileType> allowed = new HashSet<>(allowedFormats);
        return files.stream()
                .filter(file -> allowed.contains(file.getBookFileType()))
                .collect(java.util.stream.Collectors.toList());
    }

    private List<LibraryFile> findLibraryFiles(LibraryPathEntity pathEntity, LibraryEntity libraryEntity, Directory root) {
        Path libraryPath = Path.of(pathEntity.getPath());
        List<LibraryFile> libraryFiles = new ArrayList<>();

        // Track directories that contain audio files for folder-based audiobook detection
        Map<Path, List<Path>> dirAudioFiles = new HashMap<>();
        Map<Path, Boolean> dirHasNonAudioBooks = new HashMap<>();
        Set<Path> processedAsFolderAudiobook = new HashSet<>();

        visit(root, new TreeVisitor() {
            @Override
            public void visitFile(Path file) {
                String fileName = file.getFileName().toString();
                Path parentDir = file.getParent();
                BookFileType fileType = bookFileType(fileName);

                if (fileType == BookFileType.AUDIOBOOK) {
                    // Track audio files per directory for folder-based detection
                    dirAudioFiles.computeIfAbsent(parentDir, k -> new ArrayList<>()).add(file);
                } else {
                    // Track that this directory has non-audio book files
                    dirHasNonAudioBooks.put(parentDir, true);

                    // Add non-audio files immediately
                    libraryFiles.add(LibraryFile.builder()
                            .libraryEntity(libraryEntity)
                            .libraryPathEntity(pathEntity)
                            .fileSubPath(FileUtils.getRelativeSubPath(pathEntity.getPath(), file))
                            .fileName(fileName)
                            .bookFileType(fileType)
                            .build());
                }
            }

            @Override
            public void postVisitDirectory(Path dir) {
                // Check if this directory should be treated as a folder-based audiobook
                List<Path> audioFiles = dirAudioFiles.get(dir);
                boolean hasNonAudioBooks = dirHasNonAudioBooks.getOrDefault(dir, false);

                if (audioFiles != null && audioFiles.size() >= MIN_AUDIO_FILES_FOR_FOLDER_AUDIOBOOK && !hasNonAudioBooks) {
                    // Don't treat library root as audiobook folder
                    if (!dir.equals(libraryPath)) {
                        if (FileUtils.isSeriesFolder(audioFiles)) {
                            log.info("Detected series folder: {} ({} audio files with distinct titles)", dir.getFileName(), audioFiles.size());
                            addIndividualAudioFiles(audioFiles, libraryEntity, pathEntity, libraryFiles);
                        } else {
                            log.info("Detected folder-based audiobook: {} ({} audio files)", dir.getFileName(), audioFiles.size());

                            processedAsFolderAudiobook.add(dir);

                            libraryFiles.add(LibraryFile.builder()
                                    .libraryEntity(libraryEntity)
                                    .libraryPathEntity(pathEntity)
                                    .fileSubPath(FileUtils.getRelativeSubPath(pathEntity.getPath(), dir))
                                    .fileName(dir.getFileName().toString())
                                    .bookFileType(BookFileType.AUDIOBOOK)
                                    .folderBased(true)
                                    .build());
                        }
                    } else {
                        // Library root - add individual audio files
                        addIndividualAudioFiles(audioFiles, libraryEntity, pathEntity, libraryFiles);
                    }
                } else if (audioFiles != null) {
                    // Not a folder-based audiobook - add individual audio files
                    // But skip if parent was already processed as folder-based audiobook
                    boolean parentIsAudiobookFolder = false;
                    Path parent = dir.getParent();
                    while (parent != null && parent.startsWith(libraryPath)) {
                        if (processedAsFolderAudiobook.contains(parent)) {
                            parentIsAudiobookFolder = true;
                            break;
                        }
                        parent = parent.getParent();
                    }

                    if (!parentIsAudiobookFolder) {
                        addIndividualAudioFiles(audioFiles, libraryEntity, pathEntity, libraryFiles);
                    }
                }
            }

            private void addIndividualAudioFiles(List<Path> audioFiles, LibraryEntity libraryEntity,
                    LibraryPathEntity pathEntity, List<LibraryFile> libraryFiles) {
                for (Path audioFile : audioFiles) {
                    String fileName = audioFile.getFileName().toString();
                    Optional<BookFileExtension> ext = BookFileExtension.fromFileName(fileName);

                    libraryFiles.add(LibraryFile.builder()
                            .libraryEntity(libraryEntity)
                            .libraryPathEntity(pathEntity)
                            .fileSubPath(FileUtils.getRelativeSubPath(pathEntity.getPath(), audioFile))
                            .fileName(fileName)
                            .bookFileType(ext.map(BookFileExtension::getType).orElse(BookFileType.AUDIOBOOK))
                            .build());
                }
            }
        });
        return libraryFiles;
    }

    /** The walker keeps only files with a book extension, so there always is one. */
    private static BookFileType bookFileType(String fileName) {
        return BookFileExtension.fromFileName(fileName).orElseThrow().getType();
    }
}
