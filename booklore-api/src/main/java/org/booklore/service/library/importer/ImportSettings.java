package org.booklore.service.library.importer;

import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;

import java.time.Duration;

/**
 * The book import settings in effect, from {@code app.scan.import-*} with each value clamped to what the import
 * supports.
 *
 * @param pipelineEnabled   false imports new books in the sequential loop under one scan-wide transaction, as before
 * @param ioParallelism     files read from storage at once, across all scans
 * @param window            groups one scan keeps in flight, read but not yet saved included
 * @param fileTimeout       how long one file may take from the moment it starts reading
 * @param maxStuckThreads   import threads given up on but still blocked on storage at which imports stop
 * @param renderParallelism cover decodes, renders and encodes at once
 * @param heapBudgetBytes   heap that cover rendering and document parsing may reserve at once
 */
@Slf4j
public record ImportSettings(boolean pipelineEnabled, int ioParallelism, int window, Duration fileTimeout,
                             int maxStuckThreads, int renderParallelism, long heapBudgetBytes) {

    private static final long MIB = 1024 * 1024;

    /**
     * The settings for this process, logging a warning for each configured value that had to be clamped.
     *
     * @param cores        the processors available, for the default render parallelism
     * @param maxHeapBytes the maximum heap, for the heap budget
     */
    public static ImportSettings from(AppProperties.Scan scan, int cores, long maxHeapBytes) {
        int io = (int) clamp("import-io-parallelism", scan.getImportIoParallelism(), 1, 128);
        int window = scan.getImportWindow() == 0
                ? 4 * io
                : (int) clamp("import-window", scan.getImportWindow(), io, 512);
        long timeoutSeconds = clamp("import-file-timeout-seconds", scan.getImportFileTimeoutSeconds(), 10, 3600);
        int maxStuck = (int) clamp("import-max-stuck-threads", scan.getImportMaxStuckThreads(), 1, 128);
        int render = scan.getImportRenderParallelism() == 0
                ? Math.clamp(cores / 2, 1, 4)
                : (int) clamp("import-render-parallelism", scan.getImportRenderParallelism(), 1, 16);
        long maxHeapMib = maxHeapBytes / MIB;
        long heapMib = scan.getImportHeapBudgetMb() == 0
                ? Math.clamp(maxHeapMib / 4, 128, 1024)
                : clamp("import-heap-budget-mb", scan.getImportHeapBudgetMb(), 64, Math.max(64, maxHeapMib / 2));

        ImportSettings settings = new ImportSettings(scan.isImportPipelineEnabled(), io, window,
                Duration.ofSeconds(timeoutSeconds), maxStuck, render, heapMib * MIB);
        log.info("Book import: pipeline-enabled={}, io-parallelism={}, window={}, file-timeout={}s, max-stuck-threads={}, "
                        + "render-parallelism={}, heap-budget={} MiB (max heap {} MiB, {} cores)",
                settings.pipelineEnabled(), io, window, timeoutSeconds, maxStuck, render, heapMib, maxHeapMib, cores);
        return settings;
    }

    private static long clamp(String property, long value, long min, long max) {
        long clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            log.warn("app.scan.{}={} is outside {}..{}; using {}", property, value, min, max, clamped);
        }
        return clamped;
    }
}
