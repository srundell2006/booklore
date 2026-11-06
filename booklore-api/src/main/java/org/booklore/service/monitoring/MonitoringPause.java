package org.booklore.service.monitoring;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * What {@link MonitoringRegistrationService#pauseLibraries} stopped watching while files are written into libraries,
 * for {@link MonitoringRegistrationService#resume}: the watched libraries, which are registered again in full if they
 * are still there and still set to be watched, and the single folders that were registered in libraries whose watching
 * is off, which get back just those folders.
 */
public record MonitoringPause(Set<Long> watchedLibraries, Map<Path, Long> folders) {

    public static final MonitoringPause NONE = new MonitoringPause(Set.of(), Map.of());

    public MonitoringPause {
        watchedLibraries = Set.copyOf(watchedLibraries);
        folders = Map.copyOf(folders);
    }

    public boolean isEmpty() {
        return watchedLibraries.isEmpty() && folders.isEmpty();
    }
}
