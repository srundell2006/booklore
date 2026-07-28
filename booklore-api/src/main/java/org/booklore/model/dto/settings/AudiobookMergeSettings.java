package org.booklore.model.dto.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AudiobookMergeSettings {

    @Builder.Default
    private boolean enabled = false;

    /** Base URL of the m4b-merge sidecar, e.g. http://172.30.0.37:8080 */
    private String serviceUrl;

    /**
     * Staging directory as BookLore sees it. The sidecar must have the SAME
     * directory mounted at the path given by {@link #serviceStagingPath}.
     * Keep this on the same filesystem as the library so moving the finished
     * file back is a rename rather than a multi-gigabyte copy.
     */
    @Builder.Default
    private String stagingPath = "/merge";

    /** Staging directory as the sidecar sees it. */
    @Builder.Default
    private String serviceStagingPath = "/merge";

    // ---- encoding (ignored when a lossless remux is possible) ----
    @Builder.Default
    private String audioCodec = "aac";
    @Builder.Default
    private String audioBitrate = "64k";
    @Builder.Default
    private String audioSamplerate = "22050";
    @Builder.Default
    private String audioChannels = "1";

    /** e.g. "300,900": prefer 5 min chapters, hard cap 15 min, split on silence. */
    @Builder.Default
    private String maxChapterLength = "300,900";

    @Builder.Default
    private boolean useFilenamesAsChapters = false;

    /** Use --no-conversion when every source is already AAC-family. */
    @Builder.Default
    private boolean preferLossless = true;

    /** Parallel ffmpeg jobs inside a single merge. */
    @Builder.Default
    private int jobs = 2;

    /** Delete the original source files once a merge succeeds. Destructive. */
    @Builder.Default
    private boolean deleteSourcesAfterMerge = false;

    /** How long BookLore waits for a merge before giving up. */
    @Builder.Default
    private int jobTimeoutMinutes = 360;
}
