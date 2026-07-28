package org.booklore.service.audiobook;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.AudiobookMergeSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.repository.LibraryRepository;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.StandardOpenOption;
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
    /** Files we create for m4b-tool; deleted rather than restored into the library. */
    private static final java.util.Set<String> GENERATED_ASSETS = java.util.Set.of("description.txt");
    private static final long POLL_INTERVAL_MS = 5000;

    private final MergeContextLoader contextLoader;
    private final MonitoringRegistrationService monitoringRegistrationService;
    private final LibraryRepository libraryRepository;
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
                restoreSource(staged, original, manifest.isFolderBased());
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
    /**
     * Validates everything that can fail fast, so the caller can surface a real
     * error synchronously instead of the user seeing nothing happen.
     */
    public MergeContext prepare(long bookId) {
        AudiobookMergeSettings settings = getSettings();
        if (!settings.isEnabled()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Audiobook merging is not enabled");
        }
        if (settings.getServiceUrl() == null || settings.getServiceUrl().isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Audiobook merge service URL is not configured");
        }

        Path stagingRoot = Path.of(settings.getStagingPath());
        if (!Files.isDirectory(stagingRoot)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "Staging folder does not exist or is not mounted: " + stagingRoot);
        }

        // Read everything we need inside a short transaction. The merge itself runs
        // for minutes to hours, long after the JPA session is gone, so no entity may
        // be touched past this point.
        MergeContext context = contextLoader.load(bookId);

        Path sourcePath = Path.of(context.sourcePath());
        if (!Files.exists(sourcePath)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Source path does not exist: " + sourcePath);
        }

        List<Path> audioFiles = collectAudioFiles(sourcePath);
        if (audioFiles.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("No audio files found at " + sourcePath);
        }
        if (audioFiles.size() == 1 && isM4b(audioFiles.getFirst())) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Book is already a single .m4b file");
        }
        return context;
    }

    public Path mergeBook(MergeContext context, ProgressListener listener) {
        AudiobookMergeSettings settings = getSettings();
        Path sourcePath = Path.of(context.sourcePath());

        String jobKey = UUID.randomUUID().toString().replace("-", "");
        Path stagingRoot = Path.of(settings.getStagingPath());
        Path jobDir = stagingRoot.resolve(jobKey);
        Path stagedSource = jobDir.resolve("source");
        Path stagedOut = jobDir.resolve("out");

        String targetName = buildOutputName(context, sourcePath);
        Path destination = resolveDestination(sourcePath, context.folderBased(), targetName);

        boolean sourceMoved = false;
        // Staging moves files out of a watched library. Without pausing the watcher
        // it sees them as deletions (and as new files again on restore), which is
        // the same reason BookDropService unregisters libraries around an import.
        pauseMonitoring(context.libraryId());
        try {
            Files.createDirectories(stagedOut);
            writeManifest(jobDir, MergeStagingManifest.builder()
                    .bookId(context.bookId())
                    .originalPath(sourcePath.toString())
                    .folderBased(context.folderBased())
                    .destinationPath(destination.toString())
                    .createdAtEpochMs(System.currentTimeMillis())
                    .build());

            listener.onProgress(2, "Staging source files");
            stageSource(sourcePath, stagedSource, context.folderBased());
            sourceMoved = true;

            writeSidecarAssets(context, stagedSource);

            String serviceInput = toServicePath(settings, jobDir.resolve("source"));
            String serviceOutput = toServicePath(settings, stagedOut.resolve(targetName));

            listener.onProgress(5, "Submitting merge job");
            MergeJobStatus job = mergeClient.submit(settings, serviceInput, serviceOutput,
                    buildOptions(settings, context));
            log.info("Merge job {} submitted for book {} ({} source file(s), lossless={})",
                    job.getJobId(), context.bookId(), job.getSourceFileCount(), job.isLossless());

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
            moveFile(produced, destination);
            log.info("Merged audiobook for book {} -> {}", context.bookId(), destination);

            if (settings.isDeleteSourcesAfterMerge()) {
                deleteRecursively(stagedSource);
                if (context.folderBased()) {
                    deleteRecursively(sourcePath);
                }
                log.info("Deleted original sources for book {} after successful merge", context.bookId());
            } else {
                restoreSource(stagedSource, sourcePath, context.folderBased());
            }
            sourceMoved = false;

            listener.onProgress(100, "Merge complete");
            return destination;

        } catch (Exception e) {
            if (sourceMoved) {
                try {
                    restoreSource(stagedSource, sourcePath, context.folderBased());
                    log.info("Restored source files for book {} after failed merge", context.bookId());
                } catch (Exception restoreError) {
                    log.error("CRITICAL: merge failed AND source restore failed for book {}. "
                                    + "Files remain at {} and must be moved back to {} manually.",
                            context.bookId(), stagedSource, sourcePath, restoreError);
                    throw new IllegalStateException("Merge failed and sources could not be restored; "
                            + "they are at " + stagedSource, e);
                }
            }
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        } finally {
            deleteRecursively(jobDir);
            resumeMonitoring(context.libraryId());
        }
    }

    private void pauseMonitoring(Long libraryId) {
        if (libraryId == null) return;
        try {
            monitoringRegistrationService.unregisterLibrary(libraryId);
            log.info("Paused file monitoring for library {} during merge", libraryId);
        } catch (Exception e) {
            log.warn("Could not pause monitoring for library {}: {}", libraryId, e.getMessage());
        }
    }

    private void resumeMonitoring(Long libraryId) {
        if (libraryId == null) return;
        try {
            LibraryEntity library = libraryRepository.findById(libraryId).orElse(null);
            if (library == null) return;
            for (LibraryPathEntity libraryPath : library.getLibraryPaths()) {
                monitoringRegistrationService.registerLibraryPaths(libraryId, Path.of(libraryPath.getPath()));
            }
            log.info("Resumed file monitoring for library {}", libraryId);
        } catch (Exception e) {
            log.warn("Could not resume monitoring for library {}: {}", libraryId, e.getMessage());
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


    /**
     * Moves a file, tolerating CIFS/SMB.
     *
     * Files.move across docker bind mounts falls back to a copy, and the JDK's
     * copy path uses copy_file_range(2) (LinuxNativeDispatcher.directCopy0),
     * which CIFS rejects with EAGAIN -- surfacing as
     * "IOException: Resource temporarily unavailable" partway through a large
     * file. A plain stream copy uses ordinary read/write syscalls and works.
     */
    private void moveFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException e) {
            log.debug("Fast move {} -> {} failed ({}), falling back to stream copy",
                    source.getFileName(), target.getFileName(), e.getMessage());
        }
        // Copy to a temp name first so a failure never leaves a truncated file
        // sitting at the destination looking like a real one.
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        try {
            // An explicit byte-buffer loop, deliberately NOT InputStream.transferTo():
            // transferTo detects two file channels and delegates to sendfile(2)
            // (FileDispatcherImpl.transferTo0), which is exactly the kernel fast
            // path CIFS rejects with EAGAIN. A manual loop forces ordinary
            // read(2)/write(2) syscalls, which CIFS handles.
            byte[] buffer = new byte[1 << 16];
            try (java.io.InputStream in = new java.io.BufferedInputStream(Files.newInputStream(source), 1 << 16);
                 java.io.OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(partial,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE), 1 << 16)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(source);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
    }

    /**
     * Moves the source files into staging, one file at a time.
     *
     * Renaming the directory wholesale looks tempting but does not work here:
     * /audiobooks and /merge are separate docker bind mounts, and rename(2)
     * returns EXDEV across mount points even when the underlying filesystem is
     * the same. Java then falls back to a copy, which refuses to move a
     * non-empty directory. Moving regular files individually works either way --
     * Files.move transparently falls back to copy+delete per file.
     */
    private void stageSource(Path source, Path stagedSource, boolean folderBased) throws IOException {
        Files.createDirectories(stagedSource);
        if (!folderBased) {
            moveFile(source, stagedSource.resolve(source.getFileName()));
            return;
        }
        try (Stream<Path> walk = Files.walk(source)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Path relative = source.relativize(file);
                moveFile(file, stagedSource.resolve(relative));
            }
        }
    }

    /**
     * Moves staged files back to where they came from, preserving relative layout.
     * Assets we generated for m4b-tool are deleted rather than restored.
     */
    private void restoreSource(Path stagedSource, Path originalPath, boolean folderBased) throws IOException {
        if (!Files.exists(stagedSource)) {
            return;
        }
        if (!folderBased) {
            try (Stream<Path> entries = Files.list(stagedSource)) {
                for (Path entry : entries.toList()) {
                    if (GENERATED_ASSETS.contains(entry.getFileName().toString())) {
                        Files.deleteIfExists(entry);
                        continue;
                    }
                    moveFile(entry, originalPath);
                }
            }
            return;
        }
        Files.createDirectories(originalPath);
        try (Stream<Path> walk = Files.walk(stagedSource)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                if (GENERATED_ASSETS.contains(file.getFileName().toString())) {
                    Files.deleteIfExists(file);
                    continue;
                }
                moveFile(file, originalPath.resolve(stagedSource.relativize(file)));
            }
        }
    }

    /**
     * m4b-tool automatically embeds cover.jpg and description.txt found next to
     * the input, so write BookLore's metadata into the staging folder.
     */
    private void writeSidecarAssets(MergeContext context, Path stagedSource) {
        String description = context.description();
        if (description == null || description.isBlank()) return;
        try {
            Path target = stagedSource.resolve("description.txt");
            if (!Files.exists(target)) {
                Files.writeString(target, description, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("Could not write description.txt into staging: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildOptions(AudiobookMergeSettings settings, MergeContext context) {
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

        putIfPresent(options, "name", context.title());
        putIfPresent(options, "album", context.title());
        putIfPresent(options, "publisher", context.publisher());
        // --series / --series-part drive m4b-tool's sort-order generation
        putIfPresent(options, "series", context.seriesName());
        if (context.seriesNumber() != null) {
            options.put("seriesPart", trimTrailingZero(context.seriesNumber()));
        }
        if (context.publishedYear() != null) {
            options.put("year", String.valueOf(context.publishedYear()));
        }
        putIfPresent(options, "description", context.description());
        return options;
    }

    /** m4b-tool expects "2", not "2.0", for whole-numbered series parts. */
    private String trimTrailingZero(Float value) {
        if (value == value.intValue()) {
            return String.valueOf(value.intValue());
        }
        return String.valueOf(value);
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

    private String buildOutputName(MergeContext context, Path sourcePath) {
        String base = context.title();
        if (base == null || base.isBlank()) {
            base = sourcePath.getFileName().toString();
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
