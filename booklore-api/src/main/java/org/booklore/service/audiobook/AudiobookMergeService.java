package org.booklore.service.audiobook;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.AudiobookMergeSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Merges an audiobook into a single .m4b via the m4b-merge sidecar.
 *
 * The sidecar deliberately has no access to the library. BookLore stages the
 * files for one job into a dedicated merge folder that both containers share,
 * then moves the finished file back itself.
 *
 *   library ──move──▶ /merge/<jobId>/source ──sidecar──▶ /merge/<jobId>/out/x.m4b ──move──▶ library
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudiobookMergeService {

    private static final java.util.Set<String> AUDIO_EXTENSIONS = java.util.Set.of(
            "m4b", "m4a", "mp3", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "alac", "mp4");
    private static final String MANIFEST_NAME = "booklore-merge.json";
    private static final long POLL_INTERVAL_MS = 5000;

    private final BookRepository bookRepository;
    private final AppSettingService appSettingService;
    private final M4bMergeClient mergeClient;
    private final ObjectMapper objectMapper;

    public AudiobookMergeSettings getSettings() {
        AudiobookMergeSettings settings = appSettingService.getAppSettings().getAudiobookMergeSettings();
        return settings != null ? settings : AudiobookMergeSettings.builder().build();
    }

    /**
     * Recovers source files left in staging by a crash or hard restart.
     * Runs once at startup, before any new merge can claim the directory.
     */
    @PostConstruct
    public void recoverOrphanedStaging() {
        AudiobookMergeSettings settings = getSettings();
        Path stagingRoot = Path.of(settings.getStagingPath());
        if (!Files.isDirectory(stagingRoot)) {
            return;
        }
        try (Stream<Path> jobDirs = Files.list(stagingRoot)) {
            jobDirs.filter(Files::isDirectory).forEach(this::restoreStagedJob);
        } catch (IOException e) {
            log.warn("Could not scan merge staging directory {}: {}", stagingRoot, e.getMessage());
        }
    }

    private void restoreStagedJob(Path jobDir) {
        Path manifestPath = jobDir.resolve(MANIFEST_NAME);
        if (!Files.exists(manifestPath)) {
            return;
        }
        try {
            MergeStagingManifest manifest =
                    objectMapper.readValue(Files.readString(manifestPath), MergeStagingManifest.class);
            Path original = Path.of(manifest.getOriginalPath());
            Path staged = jobDir.resolve("source");

            if (Files.exists(original)) {
                log.info("Staging leftovers for book {} but original path already exists; discarding staged copy at {}",
                        manifest.getBookId(), jobDir);
            } else if (Files.exists(staged)) {
                Files.createDirectories(original.getParent());
                Files.move(staged, original, StandardCopyOption.REPLACE_EXISTING);
                log.info("Recovered orphaned merge staging: restored {} -> {}", staged, original);
            }
            deleteRecursively(jobDir);
        } catch (Exception e) {
            log.error("Failed to recover merge staging at {}: {}", jobDir, e.getMessage());
        }
    }

    /**
     * Runs a full merge for one book. Blocking: intended to be called from a
     * task thread, which supplies the progress callback.
     */
    public Path mergeBook(long bookId, ProgressListener listener) {
        AudiobookMergeSettings settings = getSettings();
        if (!settings.isEnabled()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Audiobook merging is not enabled");
        }

        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        BookFileEntity primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Book has no file to merge");
        }

        Path sourcePath = book.getFullFilePath();
        if (sourcePath == null || !Files.exists(sourcePath)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Source path does not exist: " + sourcePath);
        }

        List<Path> audioFiles = collectAudioFiles(sourcePath);
        if (audioFiles.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("No audio files found at " + sourcePath);
        }
        if (audioFiles.size() == 1 && isM4b(audioFiles.getFirst())) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Book is already a single .m4b file");
        }

        String jobKey = UUID.randomUUID().toString().replace("-", "");
        Path stagingRoot = Path.of(settings.getStagingPath());
        Path jobDir = stagingRoot.resolve(jobKey);
        Path stagedSource = jobDir.resolve("source");
        Path stagedOut = jobDir.resolve("out");

        String targetName = buildOutputName(book);
        Path destination = resolveDestination(sourcePath, primaryFile.isFolderBased(), targetName);

        boolean sourceMoved = false;
        try {
            Files.createDirectories(stagedOut);
            writeManifest(jobDir, MergeStagingManifest.builder()
                    .bookId(bookId)
                    .originalPath(sourcePath.toString())
                    .folderBased(primaryFile.isFolderBased())
                    .destinationPath(destination.toString())
                    .createdAtEpochMs(System.currentTimeMillis())
                    .build());

            listener.onProgress(2, "Staging source files");
            stageSource(sourcePath, stagedSource, primaryFile.isFolderBased());
            sourceMoved = true;

            writeSidecarAssets(book, stagedSource);

            String serviceInput = toServicePath(settings, jobDir.resolve("source"));
            String serviceOutput = toServicePath(settings, stagedOut.resolve(targetName));

            listener.onProgress(5, "Submitting merge job");
            MergeJobStatus job = mergeClient.submit(settings, serviceInput, serviceOutput,
                    buildOptions(settings, book, stagedSource));
            log.info("Merge job {} submitted for book {} ({} source file(s), lossless={})",
                    job.getJobId(), bookId, job.getSourceFileCount(), job.isLossless());

            MergeJobStatus finished = awaitCompletion(settings, job.getJobId(), listener);

            if (!finished.isSuccessful()) {
                throw new IllegalStateException("Merge " + finished.getState() + ": "
                        + (finished.getError() != null ? finished.getError() : "unknown error"));
            }

            Path produced = stagedOut.resolve(targetName);
            if (!Files.exists(produced)) {
                throw new IllegalStateException("Merge reported success but no output file was produced");
            }

            listener.onProgress(97, "Moving merged file into the library");
            Files.createDirectories(destination.getParent());
            // Same filesystem as the library, so this is a rename rather than a copy.
            Files.move(produced, destination, StandardCopyOption.REPLACE_EXISTING);
            log.info("Merged audiobook for book {} -> {}", bookId, destination);

            if (settings.isDeleteSourcesAfterMerge()) {
                deleteRecursively(stagedSource);
                log.info("Deleted original sources for book {} after successful merge", bookId);
            } else {
                restoreSource(stagedSource, sourcePath);
            }
            sourceMoved = false;

            listener.onProgress(100, "Merge complete");
            return destination;

        } catch (Exception e) {
            if (sourceMoved) {
                try {
                    restoreSource(stagedSource, sourcePath);
                    log.info("Restored source files for book {} after failed merge", bookId);
                } catch (Exception restoreError) {
                    log.error("CRITICAL: merge failed AND source restore failed for book {}. "
                                    + "Files remain at {} and must be moved back to {} manually.",
                            bookId, stagedSource, sourcePath, restoreError);
                    throw new IllegalStateException("Merge failed and sources could not be restored; "
                            + "they are at " + stagedSource, e);
                }
            }
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        } finally {
            deleteRecursively(jobDir);
        }
    }

    private MergeJobStatus awaitCompletion(AudiobookMergeSettings settings, String jobId, ProgressListener listener) {
        long deadline = System.currentTimeMillis() + settings.getJobTimeoutMinutes() * 60_000L;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                mergeClient.cancel(settings, jobId);
                throw new IllegalStateException("Merge exceeded timeout of "
                        + settings.getJobTimeoutMinutes() + " minutes");
            }
            if (listener.isCancelled()) {
                mergeClient.cancel(settings, jobId);
                throw new IllegalStateException("Merge cancelled");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mergeClient.cancel(settings, jobId);
                throw new IllegalStateException("Merge interrupted");
            }
            MergeJobStatus status = mergeClient.poll(settings, jobId);
            if (status.isTerminal()) {
                return status;
            }
            // Sidecar progress covers the encode; reserve the last few percent for the move back.
            int scaled = 5 + (int) Math.round(status.getProgress() * 0.9);
            listener.onProgress(Math.min(scaled, 95),
                    status.isLossless() ? "Remuxing (lossless)" : "Converting audio");
        }
    }

    /** Moves the source into staging. Rename on the same filesystem, so effectively free. */
    private void stageSource(Path source, Path stagedSource, boolean folderBased) throws IOException {
        if (folderBased) {
            Files.move(source, stagedSource, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.createDirectories(stagedSource);
            Files.move(source, stagedSource.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void restoreSource(Path stagedSource, Path originalPath) throws IOException {
        if (!Files.exists(stagedSource)) {
            return;
        }
        Files.createDirectories(originalPath.getParent());
        if (Files.isDirectory(stagedSource) && !Files.exists(originalPath)) {
            Files.move(stagedSource, originalPath, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try (Stream<Path> entries = Files.list(stagedSource)) {
            for (Path entry : entries.toList()) {
                Files.move(entry, originalPath.resolve(entry.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * m4b-tool automatically embeds cover.jpg and description.txt found next to
     * the input, so write BookLore's metadata into the staging folder.
     */
    private void writeSidecarAssets(BookEntity book, Path stagedSource) {
        BookMetadataEntity metadata = book.getMetadata();
        if (metadata == null) return;
        try {
            String description = metadata.getDescription();
            if (description != null && !description.isBlank() && !Files.exists(stagedSource.resolve("description.txt"))) {
                Files.writeString(stagedSource.resolve("description.txt"), description, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("Could not write description.txt into staging: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildOptions(AudiobookMergeSettings settings, BookEntity book, Path stagedSource) {
        Map<String, Object> options = new HashMap<>();
        options.put("preferLossless", settings.isPreferLossless());
        options.put("audioCodec", settings.getAudioCodec());
        options.put("audioBitrate", settings.getAudioBitrate());
        options.put("audioSamplerate", settings.getAudioSamplerate());
        options.put("audioChannels", settings.getAudioChannels());
        options.put("jobs", settings.getJobs());
        if (settings.getMaxChapterLength() != null && !settings.getMaxChapterLength().isBlank()) {
            options.put("maxChapterLength", settings.getMaxChapterLength());
        }
        if (settings.isUseFilenamesAsChapters()) {
            options.put("useFilenamesAsChapters", true);
        }

        BookMetadataEntity metadata = book.getMetadata();
        if (metadata != null) {
            putIfPresent(options, "name", metadata.getTitle());
            putIfPresent(options, "album", metadata.getTitle());
            putIfPresent(options, "publisher", metadata.getPublisher());
            // --series / --series-part drive m4b-tool's sort-order generation
            putIfPresent(options, "series", metadata.getSeriesName());
            if (metadata.getSeriesNumber() != null) {
                options.put("seriesPart", String.valueOf(metadata.getSeriesNumber()));
            }
            if (metadata.getPublishedDate() != null) {
                options.put("year", String.valueOf(metadata.getPublishedDate().getYear()));
            }
            putIfPresent(options, "description", metadata.getDescription());
        }
        return options;
    }

    private void putIfPresent(Map<String, Object> options, String key, String value) {
        if (value != null && !value.isBlank()) {
            options.put(key, value);
        }
    }

    private List<Path> collectAudioFiles(Path path) {
        List<Path> found = new ArrayList<>();
        if (Files.isRegularFile(path)) {
            if (isAudio(path)) found.add(path);
            return found;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.filter(Files::isRegularFile).filter(this::isAudio).sorted().forEach(found::add);
        } catch (IOException e) {
            log.warn("Could not scan {}: {}", path, e.getMessage());
        }
        return found;
    }

    private boolean isAudio(Path path) {
        return AUDIO_EXTENSIONS.contains(extensionOf(path));
    }

    private boolean isM4b(Path path) {
        return "m4b".equals(extensionOf(path));
    }

    private String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Folder-based books collapse to a sibling .m4b; single files replace themselves. */
    private Path resolveDestination(Path sourcePath, boolean folderBased, String targetName) {
        Path parent = folderBased ? sourcePath.getParent() : sourcePath.getParent();
        return parent.resolve(targetName);
    }

    private String buildOutputName(BookEntity book) {
        String base = null;
        if (book.getMetadata() != null && book.getMetadata().getTitle() != null
                && !book.getMetadata().getTitle().isBlank()) {
            base = book.getMetadata().getTitle();
        }
        if (base == null) {
            Path path = book.getFullFilePath();
            base = path != null ? path.getFileName().toString() : "audiobook";
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
        }
        String sanitized = base.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        if (sanitized.isBlank()) sanitized = "audiobook";
        return sanitized + ".m4b";
    }

    private String toServicePath(AudiobookMergeSettings settings, Path bookloreView) {
        Path stagingRoot = Path.of(settings.getStagingPath());
        Path relative = stagingRoot.relativize(bookloreView);
        return Path.of(settings.getServiceStagingPath()).resolve(relative).toString();
    }

    private void writeManifest(Path jobDir, MergeStagingManifest manifest) throws IOException {
        Files.writeString(jobDir.resolve(MANIFEST_NAME),
                objectMapper.writeValueAsString(manifest), StandardCharsets.UTF_8);
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", path, e.getMessage());
        }
    }

    /** Progress + cancellation hook supplied by the calling task. */
    public interface ProgressListener {
        void onProgress(int percent, String message);

        default boolean isCancelled() {
            return false;
        }
    }
}
