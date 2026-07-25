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
    /**
     * Prowlarr returns categories as objects ({"id":7020,"name":"Books/EBook"}),
     * not bare ints. Typing this as List<Integer> made Jackson throw on every
     * successful search — "Cannot deserialize value of type java.lang.Integer
     * from Object value" — so results were parsed as a failure.
     */
    private List<Category> categories;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Category {
        private Integer id;
        private String name;
    }

    /** Set server-side after scoring; serialized to the UI. */
    private Integer score;
}
