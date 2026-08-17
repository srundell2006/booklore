package org.booklore.model.dto.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Options for the copyright-page ISBN scan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IsbnScanSettings {

    /**
     * How many spine documents the wide scan reads beyond the declared
     * copyright page.
     *
     * <p>These are spine <em>documents</em>, not rendered pages — in a typical
     * novel each is a chapter or front-matter section. Higher values find more
     * ISBNs but increasingly risk matching one that belongs to another book
     * advertised in the front or back matter.
     *
     * <p>The strict "copyright page only" scan ignores this entirely.
     */
    @Builder.Default
    private int spineItemsToScan = 8;
}
