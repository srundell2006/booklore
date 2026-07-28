package org.booklore.model.dto.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EbookConversionSettings {

    @Builder.Default
    private boolean enabled = false;

    /** Base URL of the ebook-converter sidecar, e.g. http://172.30.0.36:8080 */
    private String serviceUrl;

    /** Default output format for one-click conversion. */
    @Builder.Default
    private String defaultTargetFormat = "epub";

    /** Attach the converted file to the book as an alternative format. */
    @Builder.Default
    private boolean attachAsAlternativeFormat = true;

    @Builder.Default
    private int timeoutMinutes = 15;
}
