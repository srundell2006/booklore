package org.booklore.service.catalog;

import java.util.Arrays;
import java.util.Optional;

/** The dashboard's fixed rows, by the path segment that names them. */
public enum DashboardRowKind {
    CONTINUE_READING("continue-reading"),
    CONTINUE_LISTENING("continue-listening"),
    RECENTLY_ADDED("recently-added"),
    RANDOM("random");

    private final String path;

    DashboardRowKind(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static Optional<DashboardRowKind> fromPath(String path) {
        return Arrays.stream(values()).filter(kind -> kind.path.equals(path)).findFirst();
    }
}
