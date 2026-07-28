package org.booklore.service.audiobook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

/**
 * Written into every staging directory before any file is moved.
 *
 * Source files are moved (not copied) into staging because a copy of a
 * multi-gigabyte audiobook over SMB is slow and doubles disk use. The tradeoff
 * is that a crash mid-merge would otherwise strand them, so this manifest
 * records where they came from and lets startup recovery put them back.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MergeStagingManifest {
    private Long bookId;
    /** Original location of the source file or folder, in BookLore's filesystem view. */
    private String originalPath;
    /** True when originalPath was a directory of audio files. */
    private boolean folderBased;
    /** Where the finished .m4b should end up. */
    private String destinationPath;
    private String jobId;
    private long createdAtEpochMs;
}
