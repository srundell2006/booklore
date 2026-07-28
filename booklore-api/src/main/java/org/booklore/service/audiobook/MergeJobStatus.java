package org.booklore.service.audiobook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/** Mirrors the m4b-merge sidecar's job representation. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MergeJobStatus {
    private String jobId;
    /** queued | running | completed | failed | cancelled */
    private String state;
    private double progress;
    private String inputPath;
    private String outputPath;
    private boolean lossless;
    private Integer sourceFileCount;
    private Long outputSizeBytes;
    private String error;
    private String command;
    private List<String> logTail;

    public boolean isTerminal() {
        return "completed".equals(state) || "failed".equals(state) || "cancelled".equals(state);
    }

    public boolean isSuccessful() {
        return "completed".equals(state);
    }
}
