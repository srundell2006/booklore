package org.booklore.service.acquisition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.BookAcquisitionSettings;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates the active download queues of every configured client into a
 * single Sonarr/Radarr-style activity view.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadQueueService {

    private final SabnzbdClient sabnzbdClient;
    private final QbittorrentClient qbittorrentClient;
    private final BookAcquisitionService acquisitionService;

    public List<DownloadQueueItem> getQueue() {
        BookAcquisitionSettings settings = acquisitionService.getSettings();
        List<DownloadQueueItem> items = new ArrayList<>();

        if (isConfigured(settings.getSabnzbdUrl())) {
            try {
                items.addAll(sabnzbdClient.getQueue(settings));
            } catch (Exception e) {
                log.warn("Failed to read SABnzbd queue: {}", e.getMessage());
            }
        }
        if (isConfigured(settings.getQbittorrentUrl())) {
            try {
                items.addAll(qbittorrentClient.getQueue(settings));
            } catch (Exception e) {
                log.warn("Failed to read qBittorrent queue: {}", e.getMessage());
            }
        }
        return items;
    }

    /**
     * Removes an item from its download client.
     *
     * @param deleteFiles when true, also deletes already-downloaded data
     */
    public boolean remove(String client, String id, boolean deleteFiles) {
        BookAcquisitionSettings settings = acquisitionService.getSettings();
        return switch (client == null ? "" : client.toUpperCase()) {
            case "SABNZBD" -> sabnzbdClient.deleteFromQueue(settings, id, deleteFiles);
            case "QBITTORRENT" -> qbittorrentClient.deleteTorrent(settings, id, deleteFiles);
            default -> {
                log.warn("Unknown download client for removal: {}", client);
                yield false;
            }
        };
    }

    private boolean isConfigured(String url) {
        return url != null && !url.isBlank();
    }
}
