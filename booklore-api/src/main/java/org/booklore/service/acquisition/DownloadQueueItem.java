package org.booklore.service.acquisition;

import lombok.Builder;
import lombok.Data;

/**
 * A normalized download-queue entry, unified across SABnzbd and qBittorrent.
 */
@Data
@Builder
public class DownloadQueueItem {
    /** Client-native id (nzo_id for SABnzbd, info hash for qBittorrent). */
    private String id;
    private String name;
    /** SABNZBD | QBITTORRENT */
    private String client;
    /** Raw client state, e.g. Downloading, Queued, Paused, stalledDL, uploading. */
    private String state;
    /** 0-100. */
    private double progress;
    /** Total size in bytes. */
    private Long sizeBytes;
    /** Remaining bytes still to download. */
    private Long remainingBytes;
    /** Bytes per second. */
    private Long downloadSpeed;
    /** Seconds remaining, null when unknown/stalled. */
    private Long etaSeconds;
    private String category;
    /** True once the client considers the item finished. */
    private boolean completed;
}
