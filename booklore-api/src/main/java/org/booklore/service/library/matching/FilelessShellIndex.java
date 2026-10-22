package org.booklore.service.library.matching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A library's fileless books by the identities their titles read as. Built once per rescan, or kept for a while by
 * the watcher, so that matching a new file costs a few hash lookups instead of a comparison with every fileless book
 * of the library. Safe to read while another thread takes a book out.
 */
public final class FilelessShellIndex {

    public static final FilelessShellIndex EMPTY = new FilelessShellIndex();

    private final Map<String, List<ShellCandidate>> byIdentity = new HashMap<>();
    private final Map<Long, ShellCandidate> byId = new HashMap<>();

    public static FilelessShellIndex of(Collection<ShellCandidate> shells) {
        FilelessShellIndex index = new FilelessShellIndex();
        for (ShellCandidate shell : shells) {
            if (index.byId.putIfAbsent(shell.bookId(), shell) != null) {
                continue;
            }
            for (String identity : shell.identities()) {
                index.byIdentity.computeIfAbsent(identity, k -> new ArrayList<>(1)).add(shell);
            }
        }
        return index;
    }

    /** The fileless books whose title reads as this identity. */
    public synchronized List<ShellCandidate> lookup(String identity) {
        if (identity == null || identity.isEmpty()) {
            return List.of();
        }
        List<ShellCandidate> shells = byIdentity.get(identity);
        return shells == null ? List.of() : List.copyOf(shells);
    }

    /** Takes a book out once a file was attached to it: it is no longer fileless. */
    public synchronized void remove(long bookId) {
        ShellCandidate shell = byId.remove(bookId);
        if (shell == null) {
            return;
        }
        for (String identity : shell.identities()) {
            List<ShellCandidate> shells = byIdentity.get(identity);
            if (shells != null) {
                shells.removeIf(s -> s.bookId() == bookId);
                if (shells.isEmpty()) {
                    byIdentity.remove(identity);
                }
            }
        }
    }

    public synchronized boolean contains(long bookId) {
        return byId.containsKey(bookId);
    }

    public synchronized int size() {
        return byId.size();
    }
}
