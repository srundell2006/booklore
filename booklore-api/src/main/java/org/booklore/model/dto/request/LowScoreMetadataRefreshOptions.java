package org.booklore.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LowScoreMetadataRefreshOptions {

    @Builder.Default
    private float scoreThreshold = 0.7f;

    @Builder.Default
    private int batchSize = 1000;
}
