package org.booklore.service.library;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Real filesystem access that a test can script to behave like a troubled network share
 * (failing, stalling or hiding entries), and that records every call the walk makes.
 * Paths are matched exactly as the walk builds them: library path, then resolved names.
 */
class ScriptedFileAccess extends LibraryDirectoryWalker.FileAccess {

    /** One recorded call: {@code entry} when a listing returns an entry, {@code stat} when attributes are read. */
    record Call(String kind, Path path) {
    }

    final Map<Path, IOException> listingFailures = new ConcurrentHashMap<>();
    final Set<Path> listingsFailingAfterFirstEntry = ConcurrentHashMap.newKeySet();
    /** Listings that block, ignoring interrupts, until {@link #release()}: a CIFS call on a dead share. */
    final Set<Path> stuckListings = ConcurrentHashMap.newKeySet();
    /** Listings that return their first entry, then block reading the next one until {@link #release()}. */
    final Set<Path> listingsStuckAfterFirstEntry = ConcurrentHashMap.newKeySet();
    /** Attribute reads that block until {@link #release()}: a file whose oplock break never completes. */
    final Set<Path> stuckAttributeReads = ConcurrentHashMap.newKeySet();
    /** Readability checks that block until {@link #release()}. */
    final Set<Path> stuckReadabilityChecks = ConcurrentHashMap.newKeySet();
    final Map<Path, Duration> listingDelays = new ConcurrentHashMap<>();
    final Map<Path, CyclicBarrier> listingBarriers = new ConcurrentHashMap<>();
    /** Thrown for every attribute read of the path, with or without following links. */
    final Map<Path, IOException> attributeFailures = new ConcurrentHashMap<>();
    /** Thrown only when following links: a link whose target is on a share that is down. */
    final Map<Path, IOException> targetFailures = new ConcurrentHashMap<>();
    /** Delay for reading the attributes of each entry of the given directory. */
    final Map<Path, Duration> entryAttributeDelays = new ConcurrentHashMap<>();
    final Set<Path> unreadable = ConcurrentHashMap.newKeySet();
    /** Entries left out of every listing of their directory but its first: a share unmounted meanwhile, say. */
    final Set<Path> unlistedOnRelisting = ConcurrentHashMap.newKeySet();
    /** Lists entries in name order instead of the filesystem's. */
    volatile boolean listInNameOrder;
    /** Answers lookups as a filesystem that matches names regardless of case would. */
    volatile boolean caseInsensitive;

    final Queue<Path> listed = new ConcurrentLinkedQueue<>();
    final Queue<Path> attributesRead = new ConcurrentLinkedQueue<>();
    final Queue<Path> readabilityChecked = new ConcurrentLinkedQueue<>();
    final Queue<Path> existenceChecked = new ConcurrentLinkedQueue<>();
    final Queue<Call> calls = new ConcurrentLinkedQueue<>();
    /** Calls that got stuck, of any kind. */
    final AtomicInteger stuckCount = new AtomicInteger();
    /** Listings in progress, not counting stuck ones. */
    final AtomicInteger activeListings = new AtomicInteger();
    final AtomicInteger maxActiveListings = new AtomicInteger();

    private final CountDownLatch release = new CountDownLatch(1);

    void release() {
        release.countDown();
    }

    @Override
    DirectoryStream<Path> newDirectoryStream(Path dir) throws IOException {
        boolean relisting = listed.contains(dir);
        listed.add(dir);
        if (stuckListings.contains(dir)) {
            stuckCount.incrementAndGet();
            blockUninterruptibly();
        }
        maxActiveListings.accumulateAndGet(activeListings.incrementAndGet(), Math::max);
        try {
            CyclicBarrier barrier = listingBarriers.get(dir);
            if (barrier != null) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IOException("Barrier for " + dir + " was not reached", e);
                }
            }
            sleep(listingDelays.get(dir));
            IOException failure = listingFailures.get(dir);
            if (failure != null) {
                throw failure;
            }
            return recording(super.newDirectoryStream(dir), relisting, listingsFailingAfterFirstEntry.contains(dir),
                    listingsStuckAfterFirstEntry.contains(dir));
        } finally {
            activeListings.decrementAndGet();
        }
    }

    @Override
    BasicFileAttributes readAttributes(Path path, LinkOption... options) throws IOException {
        attributesRead.add(path);
        calls.add(new Call("stat", path));
        if (stuckAttributeReads.contains(path)) {
            stuckCount.incrementAndGet();
            blockUninterruptibly();
        }
        if (path.getParent() != null) {
            sleep(entryAttributeDelays.get(path.getParent()));
        }
        IOException failure = attributeFailures.get(path);
        if (failure == null && !Arrays.asList(options).contains(LinkOption.NOFOLLOW_LINKS)) {
            failure = targetFailures.get(path);
        }
        if (failure != null) {
            throw failure;
        }
        return super.readAttributes(path, options);
    }

    @Override
    boolean isReadable(Path file) {
        readabilityChecked.add(file);
        if (stuckReadabilityChecks.contains(file)) {
            stuckCount.incrementAndGet();
            blockUninterruptibly();
        }
        return !unreadable.contains(file) && super.isReadable(file);
    }

    @Override
    boolean exists(Path path) {
        existenceChecked.add(path);
        if (!caseInsensitive) {
            return super.exists(path);
        }
        String name = path.getFileName().toString();
        try (Stream<Path> siblings = Files.list(path.getParent())) {
            return siblings.anyMatch(sibling -> sibling.getFileName().toString().equalsIgnoreCase(name));
        } catch (IOException e) {
            return false;
        }
    }

    private void blockUninterruptibly() {
        boolean interrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(Duration delay) throws InterruptedIOException {
        if (delay == null) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    /**
     * Records each entry as the listing returns it, optionally sorted, optionally leaving out entries unlisted on
     * relisting, optionally failing or stuck after the first.
     */
    private DirectoryStream<Path> recording(DirectoryStream<Path> stream, boolean relisting, boolean failAfterFirstEntry,
                                            boolean stuckAfterFirstEntry) {
        Iterator<Path> source;
        if (listInNameOrder || relisting) {
            List<Path> entries = new ArrayList<>();
            stream.forEach(entry -> {
                if (!relisting || !unlistedOnRelisting.contains(entry)) {
                    entries.add(entry);
                }
            });
            if (listInNameOrder) {
                entries.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            }
            source = entries.iterator();
        } else {
            source = stream.iterator();
        }
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return new Iterator<>() {
                    private boolean first = true;

                    @Override
                    public boolean hasNext() {
                        return failAfterFirstEntry || source.hasNext();
                    }

                    @Override
                    public Path next() {
                        if (failAfterFirstEntry && !(first && source.hasNext())) {
                            throw new DirectoryIteratorException(new IOException("Connection reset by peer"));
                        }
                        if (stuckAfterFirstEntry && !first) {
                            stuckCount.incrementAndGet();
                            blockUninterruptibly();
                        }
                        first = false;
                        Path entry = source.next();
                        calls.add(new Call("entry", entry));
                        return entry;
                    }
                };
            }

            @Override
            public void close() throws IOException {
                stream.close();
            }
        };
    }
}
