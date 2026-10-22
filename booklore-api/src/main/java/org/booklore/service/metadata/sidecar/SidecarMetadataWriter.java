package org.booklore.service.metadata.sidecar;

import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.dto.settings.SidecarSettings;
import org.booklore.model.dto.sidecar.SidecarMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

@Slf4j
@Service
public class SidecarMetadataWriter {

    private final AppProperties appProperties;
    private final SidecarMetadataMapper mapper;
    private final FileService fileService;
    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;

    public SidecarMetadataWriter(AppProperties appProperties, SidecarMetadataMapper mapper, FileService fileService, AppSettingService appSettingService) {
        this.appProperties = appProperties;
        this.mapper = mapper;
        this.fileService = fileService;
        this.appSettingService = appSettingService;
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .configure(SerializationFeature.INDENT_OUTPUT, true)
                .build();
    }

    /**
     * Writes the sidecar only when no sidecar json exists yet for the book.
     * Used by bookdrop imports as a safety net when the metadata-update step
     * detected no changes and therefore skipped its own sidecar write.
     */
    public void writeSidecarMetadataIfMissing(BookEntity book) {
        Path bookPath = book.getFullFilePath();
        if (bookPath == null) return;
        try {
            if (java.nio.file.Files.exists(getSidecarPath(bookPath))) return;
        } catch (Exception ignored) {
            // fall through and attempt the write
        }
        writeSidecarMetadata(book);
    }

    public void writeSidecarMetadata(BookEntity book) {
        prepare(book).ifPresent(this::execute);
    }

    /**
     * A book's sidecar file and cover copy, worked out while the book's session is open so that only file work is
     * left for {@link #execute}.
     *
     * @param coverSource  the stored cover to copy next to the book, or null when the settings leave the cover out
     * @param coverTarget  where to copy it, or null as above
     * @param keepExisting whether a sidecar file or cover copy already next to the book stays as it is, as a scan
     *                     leaves it ({@link #prepareForScan})
     */
    public record SidecarJob(Long bookId, Path bookPath, Path sidecarPath, String json, Path coverSource, Path coverTarget,
                             boolean keepExisting) {
        /** A job that writes over what is there, as an update of the book's metadata does. */
        public SidecarJob(Long bookId, Path bookPath, Path sidecarPath, String json, Path coverSource, Path coverTarget) {
            this(bookId, bookPath, sidecarPath, json, coverSource, coverTarget, false);
        }
    }

    /**
     * The database part of {@link #writeSidecarMetadata}: checks the storage and settings and builds the sidecar from
     * the book's metadata, which needs the book's session. Reads and writes no files.
     *
     * @return empty when no sidecar is to be written
     */
    public Optional<SidecarJob> prepare(BookEntity book) {
        if (!appProperties.isLocalStorage()) {
            return Optional.empty();
        }
        if (book == null || book.getMetadata() == null) {
            log.warn("Cannot write sidecar metadata: book or metadata is null");
            return Optional.empty();
        }

        SidecarSettings settings = getSidecarSettings();
        if (settings == null || !settings.isEnabled()) {
            log.debug("Sidecar metadata is disabled");
            return Optional.empty();
        }

        Path bookPath = book.getFullFilePath();
        if (bookPath == null) {
            log.warn("Cannot write sidecar metadata: book file does not exist");
            return Optional.empty();
        }

        String coverFileName = null;
        Path coverSource = null;
        Path coverTarget = null;
        if (settings.isIncludeCoverFile()) {
            coverFileName = mapper.getCoverFileName(bookPath);
            coverSource = Path.of(fileService.getCoverFile(book.getId()));
            coverTarget = bookPath.getParent().resolve(coverFileName);
        }

        SidecarMetadata sidecarMetadata = mapper.toSidecarMetadata(book.getMetadata(), coverFileName);
        String json = objectMapper.writeValueAsString(sidecarMetadata);
        json = json.replace(" : ", ": ").replace("[ ]", "[]");
        return Optional.of(new SidecarJob(book.getId(), bookPath, getSidecarPath(bookPath), json, coverSource, coverTarget));
    }

    /**
     * {@link #prepare} for a book a scan imports, whose sidecar must not replace one already next to its file.
     * <p>
     * Such a sidecar is the one thing that still says what the file was: a book whose file a scan imports as new may
     * have been in the library before, with metadata its owner curated and the identifiers that tie it to its old
     * book, written next to the file. Files that share a base name in a folder also share their sidecar, so the second
     * format of a book, or another book's file of that name, would write over the first. A scan keeps what is there;
     * updating the book's metadata, or exporting it, writes the sidecar as before.
     */
    public Optional<SidecarJob> prepareForScan(BookEntity book) {
        return prepare(book).map(job -> new SidecarJob(job.bookId(), job.bookPath(), job.sidecarPath(), job.json(),
                job.coverSource(), job.coverTarget(), true));
    }

    /** The file part of {@link #writeSidecarMetadata}: copies the cover and writes the sidecar next to the book. */
    public void execute(SidecarJob job) {
        try {
            if (!Files.exists(job.bookPath())) {
                log.warn("Cannot write sidecar metadata: book file does not exist");
                return;
            }

            if (job.coverTarget() != null) {
                writeCoverFile(job);
            }

            if (job.keepExisting()) {
                // Created only if absent, in one step, so a sidecar written meanwhile is not replaced either
                try {
                    Files.writeString(job.sidecarPath(), job.json(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException e) {
                    log.info("Kept the sidecar metadata already at {} for book ID {}: a scan does not replace it", job.sidecarPath(), job.bookId());
                    return;
                }
            } else {
                Files.writeString(job.sidecarPath(), job.json());
            }

            log.info("Wrote sidecar metadata to: {}", job.sidecarPath());
        } catch (IOException e) {
            log.error("Failed to write sidecar metadata for book ID {}: {}", job.bookId(), e.getMessage());
        }
    }

    public void deleteSidecarFiles(Path bookPath) {
        if (bookPath == null) {
            return;
        }

        try {
            Path sidecarPath = getSidecarPath(bookPath);
            if (Files.exists(sidecarPath)) {
                Files.delete(sidecarPath);
                log.info("Deleted sidecar file: {}", sidecarPath);
            }

            Path coverPath = getCoverPath(bookPath);
            if (Files.exists(coverPath)) {
                Files.delete(coverPath);
                log.info("Deleted sidecar cover file: {}", coverPath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete sidecar files for {}: {}", bookPath, e.getMessage());
        }
    }

    public void moveSidecarFiles(Path oldBookPath, Path newBookPath) {
        if (oldBookPath == null || newBookPath == null) {
            return;
        }

        try {
            Path oldSidecarPath = getSidecarPath(oldBookPath);
            if (Files.exists(oldSidecarPath)) {
                Path newSidecarPath = getSidecarPath(newBookPath);
                Files.createDirectories(newSidecarPath.getParent());
                Files.move(oldSidecarPath, newSidecarPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Moved sidecar file from {} to {}", oldSidecarPath, newSidecarPath);
            }

            Path oldCoverPath = getCoverPath(oldBookPath);
            if (Files.exists(oldCoverPath)) {
                Path newCoverPath = getCoverPath(newBookPath);
                Files.move(oldCoverPath, newCoverPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Moved sidecar cover from {} to {}", oldCoverPath, newCoverPath);
            }
        } catch (IOException e) {
            log.warn("Failed to move sidecar files from {} to {}: {}", oldBookPath, newBookPath, e.getMessage());
        }
    }

    public Path getSidecarPath(Path bookPath) {
        return bookPath.getParent().resolve(SidecarFileNames.baseName(bookPath) + ".metadata.json");
    }

    public Path getCoverPath(Path bookPath) {
        return bookPath.getParent().resolve(SidecarFileNames.baseName(bookPath) + ".cover.jpg");
    }

    private void writeCoverFile(SidecarJob job) {
        try {
            if (Files.exists(job.coverSource())) {
                if (job.keepExisting()) {
                    Files.copy(job.coverSource(), job.coverTarget());
                } else {
                    Files.copy(job.coverSource(), job.coverTarget(), StandardCopyOption.REPLACE_EXISTING);
                }
                log.info("Wrote cover file to: {}", job.coverTarget());
            }
        } catch (FileAlreadyExistsException e) {
            log.info("Kept the cover file already at {} for book ID {}: a scan does not replace it", job.coverTarget(), job.bookId());
        } catch (IOException e) {
            log.warn("Failed to write cover file for book ID {}: {}", job.bookId(), e.getMessage());
        }
    }

    private SidecarSettings getSidecarSettings() {
        MetadataPersistenceSettings settings = appSettingService.getAppSettings().getMetadataPersistenceSettings();
        return settings != null ? settings.getSidecarSettings() : null;
    }

    public boolean isWriteOnUpdateEnabled() {
        SidecarSettings settings = getSidecarSettings();
        return settings != null && settings.isEnabled() && settings.isWriteOnUpdate();
    }

    public boolean isWriteOnScanEnabled() {
        SidecarSettings settings = getSidecarSettings();
        return settings != null && settings.isEnabled() && settings.isWriteOnScan();
    }
}
