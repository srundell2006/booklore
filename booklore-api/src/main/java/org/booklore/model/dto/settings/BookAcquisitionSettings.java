package org.booklore.model.dto.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookAcquisitionSettings {

    @Builder.Default
    private boolean enabled = false;

    // Prowlarr
    private String prowlarrUrl;
    private String prowlarrApiKey;
    /** Newznab/Torznab categories to search. 7000/7020 = Books/EBook, 3030 = Audiobook. */
    @Builder.Default
    private String searchCategories = "7000,7020";

    // SABnzbd
    private String sabnzbdUrl;
    private String sabnzbdApiKey;
    @Builder.Default
    private String sabnzbdCategory = "books";

    // qBittorrent
    private String qbittorrentUrl;
    private String qbittorrentUsername;
    private String qbittorrentPassword;
    @Builder.Default
    private String qbittorrentCategory = "books";

    // Automation
    @Builder.Default
    private boolean autoGrab = true;
    /** Minimum score (0-100) a release must reach for automatic grabbing. */
    @Builder.Default
    private int autoGrabMinScore = 60;
    /** Preferred formats in priority order, comma separated. */
    @Builder.Default
    private String formatPriority = "epub,azw3,mobi,pdf";
    /** Prefer usenet over torrents when scores are close. */
    @Builder.Default
    private boolean preferUsenet = true;
    /** Max size in MB for a plausible ebook release (guards against mislabeled bundles). */
    @Builder.Default
    private int maxSizeMb = 200;
}
