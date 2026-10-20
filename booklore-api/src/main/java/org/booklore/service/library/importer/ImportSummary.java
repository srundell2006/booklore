package org.booklore.service.library.importer;

import java.time.Duration;

/**
 * What one import of new book groups did. A group is the files that become one book.
 *
 * @param groups                groups handed to the import
 * @param added                 books saved
 * @param failed                groups that could not be read or saved; they are new again on the next scan
 * @param timedOut              groups whose file did not finish reading within its deadline, or found no cover
 *                              rendering capacity before it
 * @param notStarted            groups never read or never saved because the import stopped or skipped them
 * @param attachmentsSkipped    additional formats of saved books left for the next rescan to attach
 * @param stoppedStorageStuck   the import stopped because too many import threads were stuck on storage
 * @param stoppedDatabaseErrors the import stopped because saving kept failing
 * @param interrupted           the import stopped because its thread was interrupted or the application is stopping
 * @param slowestFileMillis     the longest any file that finished took to read
 * @param legacy                the groups went through the sequential loop, which counts nothing but the groups
 */
public record ImportSummary(int groups, int added, int failed, int timedOut, int notStarted, int attachmentsSkipped,
                            boolean stoppedStorageStuck, boolean stoppedDatabaseErrors, boolean interrupted,
                            long slowestFileMillis, Duration elapsed, boolean legacy) {

    /** New files left for the next scan because storage was too slow or the import stopped before them. */
    public int skippedForRetry() {
        return timedOut + notStarted;
    }

    /** The summary of the sequential loop, which keeps no counts. */
    public static ImportSummary legacy(int groups, Duration elapsed) {
        return new ImportSummary(groups, 0, 0, 0, 0, 0, false, false, false, 0, elapsed, true);
    }
}
