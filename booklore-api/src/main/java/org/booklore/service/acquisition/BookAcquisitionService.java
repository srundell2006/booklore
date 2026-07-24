package org.booklore.service.acquisition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.BookAcquisitionSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.WantedBookStatus;
import org.booklore.repository.WantedBookRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookAcquisitionService {

    private final WantedBookRepository wantedBookRepository;
    private final ProwlarrClient prowlarrClient;
    private final ReleaseScorer releaseScorer;
    private final SabnzbdClient sabnzbdClient;
    private final QbittorrentClient qbittorrentClient;
    private final AppSettingService appSettingService;

    public BookAcquisitionSettings getSettings() {
        BookAcquisitionSettings settings = appSettingService.getAppSettings().getBookAcquisitionSettings();
        return settings != null ? settings : BookAcquisitionSettings.builder().build();
    }

    /**
     * Searches Prowlarr for a wanted book and returns scored, sorted releases.
     */
    public List<ProwlarrRelease> searchReleases(Long wantedBookId) {
        WantedBookEntity wanted = wantedBookRepository.findById(wantedBookId)
                .orElseThrow(() -> ApiError.GENERIC_BAD_REQUEST.createException("Wanted book not found: " + wantedBookId));
        BookAcquisitionSettings settings = requireConfigured();

        String query = buildQuery(wanted);
        List<ProwlarrRelease> releases = prowlarrClient.search(settings, query);

        wanted.setLastSearchAt(Instant.now());
        wanted.setSearchAttempts(wanted.getSearchAttempts() + 1);
        wantedBookRepository.save(wanted);

        return releaseScorer.scoreAndSort(releases, wanted, settings);
    }

    /**
     * Sends a specific release to the appropriate download client and marks the book GRABBED.
     */
    public WantedBookEntity grabRelease(Long wantedBookId, ProwlarrRelease release) {
        WantedBookEntity wanted = wantedBookRepository.findById(wantedBookId)
                .orElseThrow(() -> ApiError.GENERIC_BAD_REQUEST.createException("Wanted book not found: " + wantedBookId));
        BookAcquisitionSettings settings = requireConfigured();

        boolean isUsenet = "usenet".equalsIgnoreCase(release.getProtocol());
        String downloadId = null;
        boolean success;

        if (isUsenet) {
            String url = release.getDownloadUrl();
            if (url == null || url.isBlank()) {
                throw ApiError.GENERIC_BAD_REQUEST.createException("Release has no download URL");
            }
            downloadId = sabnzbdClient.addNzb(settings, url, release.getTitle());
            success = downloadId != null;
        } else {
            String url = release.getMagnetUrl() != null && !release.getMagnetUrl().isBlank()
                    ? release.getMagnetUrl() : release.getDownloadUrl();
            if (url == null || url.isBlank()) {
                throw ApiError.GENERIC_BAD_REQUEST.createException("Release has no magnet/download URL");
            }
            success = qbittorrentClient.addTorrent(settings, url);
        }

        if (!success) {
            wanted.setFailureReason("Failed to send release to " + (isUsenet ? "SABnzbd" : "qBittorrent"));
            wantedBookRepository.save(wanted);
            throw ApiError.INTERNAL_SERVER_ERROR.createException(wanted.getFailureReason());
        }

        wanted.setStatus(WantedBookStatus.GRABBED);
        wanted.setGrabbedAt(Instant.now());
        wanted.setGrabbedReleaseTitle(release.getTitle());
        wanted.setGrabbedIndexer(release.getIndexer());
        wanted.setDownloadClient(isUsenet ? "SABNZBD" : "QBITTORRENT");
        wanted.setDownloadId(downloadId);
        wanted.setFailureReason(null);
        log.info("Grabbed '{}' for wanted book '{}' via {}", release.getTitle(), wanted.getTitle(), wanted.getDownloadClient());
        return wantedBookRepository.save(wanted);
    }

    /**
     * Automation entry point: searches every WANTED book and auto-grabs the
     * best release when it clears the configured score threshold.
     */
    public void searchAllWanted() {
        BookAcquisitionSettings settings = getSettings();
        if (!settings.isEnabled()) {
            log.debug("Book acquisition disabled; skipping automatic search.");
            return;
        }
        List<WantedBookEntity> wantedBooks = wantedBookRepository.findAllByStatus(WantedBookStatus.WANTED);
        log.info("Automatic wanted-book search starting: {} books", wantedBooks.size());

        for (WantedBookEntity wanted : wantedBooks) {
            try {
                List<ProwlarrRelease> releases = searchReleases(wanted.getId());
                if (releases.isEmpty()) {
                    continue;
                }
                ProwlarrRelease best = releases.getFirst();
                if (settings.isAutoGrab() && best.getScore() != null && best.getScore() >= settings.getAutoGrabMinScore()) {
                    grabRelease(wanted.getId(), best);
                } else {
                    log.info("Best release for '{}' scored {} (threshold {}); not auto-grabbing",
                            wanted.getTitle(), best.getScore(), settings.getAutoGrabMinScore());
                }
                // Be polite to indexers
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Automatic search failed for wanted book '{}': {}", wanted.getTitle(), e.getMessage());
            }
        }
        log.info("Automatic wanted-book search finished");
    }

    /**
     * Called after a successful bookdrop import: if the imported book matches a
     * GRABBED (or WANTED) entry, mark it IMPORTED and link the book id.
     */
    public void markImportedIfWanted(BookEntity book) {
        if (book == null || book.getMetadata() == null || book.getMetadata().getTitle() == null) {
            return;
        }
        List<WantedBookEntity> candidates = wantedBookRepository.findAllByStatusIn(
                List.of(WantedBookStatus.GRABBED, WantedBookStatus.WANTED));
        if (candidates.isEmpty()) {
            return;
        }

        String importedTitle = normalize(book.getMetadata().getTitle());
        Set<String> importedTokens = tokens(importedTitle);

        for (WantedBookEntity wanted : candidates) {
            Set<String> wantedTokens = tokens(normalize(wanted.getTitle()));
            if (wantedTokens.isEmpty()) continue;
            long matched = wantedTokens.stream().filter(importedTokens::contains).count();
            double ratio = (double) matched / wantedTokens.size();
            if (ratio >= 0.8) {
                wanted.setStatus(WantedBookStatus.IMPORTED);
                wanted.setImportedBookId(book.getId());
                wantedBookRepository.save(wanted);
                log.info("Wanted book '{}' marked IMPORTED (matched imported book id={})", wanted.getTitle(), book.getId());
                return;
            }
        }
    }

    private BookAcquisitionSettings requireConfigured() {
        BookAcquisitionSettings settings = getSettings();
        if (settings.getProwlarrUrl() == null || settings.getProwlarrUrl().isBlank()
                || settings.getProwlarrApiKey() == null || settings.getProwlarrApiKey().isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Prowlarr is not configured in acquisition settings");
        }
        return settings;
    }

    private String buildQuery(WantedBookEntity wanted) {
        StringBuilder query = new StringBuilder(wanted.getTitle());
        if (wanted.getAuthor() != null && !wanted.getAuthor().isBlank()) {
            query.append(" ").append(wanted.getAuthor());
        }
        return query.toString();
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ");
    }

    private Set<String> tokens(String value) {
        if (value == null) return Set.of();
        return java.util.Arrays.stream(value.split("\\s+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toSet());
    }
}
