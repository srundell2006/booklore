package org.booklore.service.acquisition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProwlarrRelease {
    private String guid;
    private String title;
    private String indexer;
    private Integer indexerId;
    private Long size;
    private String downloadUrl;
    private String magnetUrl;
    private String infoUrl;
    private String protocol;      // "usenet" | "torrent"
    private Integer seeders;
    private Integer leechers;
    private List<Integer> categories;

    /** Set server-side after scoring; serialized to the UI. */
    private Integer score;
}
