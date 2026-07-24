package org.booklore.service.acquisition;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.BookAcquisitionSettings;
import org.booklore.model.entity.WantedBookEntity;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scores Prowlarr releases against a wanted book (0-100).
 * Title token overlap and author presence dominate; format preference,
 * protocol preference, seeders and size sanity refine the ranking.
 */
@Slf4j
@Service
public class ReleaseScorer {

    public List<ProwlarrRelease> scoreAndSort(List<ProwlarrRelease> releases, WantedBookEntity wanted, BookAcquisitionSettings settings) {
        for (ProwlarrRelease release : releases) {
            release.setScore(score(release, wanted, settings));
        }
        return releases.stream()
                .sorted(Comparator.comparing(ProwlarrRelease::getScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    int score(ProwlarrRelease release, WantedBookEntity wanted, BookAcquisitionSettings settings) {
        if (release.getTitle() == null) return 0;
        String releaseTitle = normalize(release.getTitle());
        int score = 0;

        // Title token overlap: up to 45 points
        Set<String> wantedTokens = tokens(wanted.getTitle());
        if (!wantedTokens.isEmpty()) {
            long matched = wantedTokens.stream().filter(releaseTitle::contains).count();
            score += (int) (45.0 * matched / wantedTokens.size());
        }

        // Author presence: up to 25 points
        if (wanted.getAuthor() != null && !wanted.getAuthor().isBlank()) {
            Set<String> authorTokens = tokens(wanted.getAuthor());
            if (!authorTokens.isEmpty()) {
                long matched = authorTokens.stream().filter(releaseTitle::contains).count();
                score += (int) (25.0 * matched / authorTokens.size());
            }
        } else {
            score += 12; // no author to check; neutral midpoint
        }

        // Format preference: up to 15 points by priority order
        String[] formats = settings.getFormatPriority() == null
                ? new String[]{"epub"}
                : settings.getFormatPriority().toLowerCase(Locale.ROOT).split(",");
        for (int i = 0; i < formats.length; i++) {
            String format = formats[i].trim();
            if (!format.isEmpty() && releaseTitle.contains(format)) {
                score += Math.max(15 - (i * 4), 3);
                break;
            }
        }

        // Protocol preference: 8 points
        boolean isUsenet = "usenet".equalsIgnoreCase(release.getProtocol());
        if (settings.isPreferUsenet() == isUsenet) {
            score += 8;
        }

        // Torrent health: up to 7 points; dead torrents are heavily punished
        if (!isUsenet) {
            int seeders = release.getSeeders() == null ? 0 : release.getSeeders();
            if (seeders == 0) {
                score -= 40;
            } else {
                score += Math.min(seeders, 7);
            }
        } else {
            score += 4;
        }

        // Size sanity: releases over the cap are almost certainly bundles/mislabels
        long maxBytes = (long) settings.getMaxSizeMb() * 1024 * 1024;
        if (release.getSize() != null && release.getSize() > maxBytes) {
            score -= 50;
        }

        // Obvious bundle markers
        if (releaseTitle.contains("collection") || releaseTitle.contains("bundle")
                || releaseTitle.contains("mega pack") || releaseTitle.contains("megapack")) {
            score -= 30;
        }

        return Math.max(0, Math.min(100, score));
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ");
    }

    private Set<String> tokens(String value) {
        if (value == null) return Set.of();
        return Arrays.stream(normalize(value).split("\\s+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toSet());
    }
}
