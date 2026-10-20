package org.booklore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {
    private String pathConfig;
    private String bookdropFolder;
    private String version;
    private RemoteAuth remoteAuth;
    private Boolean forceDisableOidc = false;

    /**
     * Type of disk storage where library files are stored.
     * Defaults to LOCAL. Set to NETWORK if using NFS, SMB/CIFS, or other network-mounted storage.
     * Some features like file move/reorganization are disabled on network storage due to
     * unreliable atomic operations that can cause data corruption or loss.
     */
    private String diskType = "LOCAL";

    private Scan scan = new Scan();

    public boolean isLocalStorage() {
        return "LOCAL".equalsIgnoreCase(diskType);
    }

    @Getter
    @Setter
    public static class RemoteAuth {
        private boolean enabled;
        private boolean createNewUsers;
        private String headerName;
        private String headerUser;
        private String headerEmail;
        private String headerGroups;
        private String adminGroup;
        private String groupsDelimiter = "\\s+";  // Default to whitespace for backward compatibility
    }

    @Getter
    @Setter
    public static class Scan {
        /**
         * How many directories a library scan lists at once (clamped to 1..64). Listing is
         * bound by filesystem round trips, so on network storage this can sit well above
         * the core count.
         */
        private int walkParallelism = 16;

        /**
         * How long a directory listing may wait on one filesystem call before the scan gives
         * up on that directory and reports it as not scanned. A large directory that keeps
         * making progress is never cut off.
         */
        private int directoryTimeoutSeconds = 60;

        /**
         * Whether library scans read new books in parallel and save each in a transaction of
         * its own. False brings back the sequential loop under one scan-wide transaction.
         */
        private boolean importPipelineEnabled = true;

        /**
         * How many new book files are read from storage at once, shared by every scan (clamped
         * to 1..128). Reading is bound by filesystem round trips, so on network storage this can
         * sit well above the core count; the threads are platform threads.
         */
        private int importIoParallelism = 16;

        /**
         * How many book groups one scan keeps in flight, read but not yet saved included. 0 means
         * four times the IO parallelism; other values are clamped to IO parallelism..512.
         */
        private int importWindow = 0;

        /**
         * How long one file may take once it has started reading before it is skipped and left
         * for the next scan (clamped to 10..3600).
         */
        private int importFileTimeoutSeconds = 180;

        /**
         * How many import threads given up on but still blocked on storage, across the process,
         * stop imports until they return (clamped to 1..128).
         */
        private int importMaxStuckThreads = 16;

        /**
         * How many cover decodes, renders and encodes run at once. 0 means half the cores,
         * between 1 and 4; other values are clamped to 1..16.
         */
        private int importRenderParallelism = 0;

        /**
         * Heap, in MiB, that cover rendering and document parsing may reserve at once. 0 means a
         * quarter of the maximum heap, between 128 and 1024; other values are clamped to 64..half
         * the maximum heap.
         */
        private int importHeapBudgetMb = 0;
    }
}
