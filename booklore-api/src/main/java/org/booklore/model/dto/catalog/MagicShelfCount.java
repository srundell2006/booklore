package org.booklore.model.dto.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One magic shelf's count. {@code count} is always written, as null when there is none; {@code reasons} only for
 * UNSUPPORTED.
 */
public record MagicShelfCount(
        Status status,
        Long count,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> reasons
) {
    public enum Status {
        /** The count is exact. */
        OK,
        /** Some rule needs the client; {@code reasons} lists them as {@code field:operator}. */
        UNSUPPORTED,
        /** filterJson does not parse; the count is 0, as the client shows it. */
        INVALID,
        /** The query failed or timed out. */
        ERROR
    }

    public static MagicShelfCount ok(long count) {
        return new MagicShelfCount(Status.OK, count, null);
    }

    public static MagicShelfCount unsupported(List<String> reasons) {
        return new MagicShelfCount(Status.UNSUPPORTED, null, List.copyOf(reasons));
    }

    public static MagicShelfCount invalid() {
        return new MagicShelfCount(Status.INVALID, 0L, null);
    }

    public static MagicShelfCount error() {
        return new MagicShelfCount(Status.ERROR, null, null);
    }
}
