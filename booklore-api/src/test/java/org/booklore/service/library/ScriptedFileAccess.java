package org.booklore.service.library;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real filesystem access that a test can script to behave like a troubled network share
 * (failing, stalling or hiding entries), and that records every call the walk makes.
 * Paths are matched exactly as the walk builds them: library path, then resolved names.
 */
class ScriptedFileAccess extends LibraryDirectoryWalker.FileAccess {

    final Map<Path, IOException> listingFailures = new ConcurrentHashMap<>();
    final Set<Path> listingsFailingAfterFirstEntry = ConcurrentHashMap.newKeySet();
    /** Listings that block, ignoring interrupts, until {@link #release()}: a CIFS call on a dead share. */
    final Set<Path> stuckListings = ConcurrentHashMap.newKeySet();
    final Map<Path, Duration> listingDelays = new ConcurrentHashMap<>();
    final Map<Path, CyclicBarrier> listingBarriers = new ConcurrentHashMap<>();
    final Map<Path, IOException> attributeFailures = new ConcurrentHashMap<>();
    /** Delay for reading the attributes of each entry of the given directory. */
    final Map<Path, Duration> entryAttributeDelays = new ConcurrentHashMap<>();
    final Set<Path> unreadable = ConcurrentHashMap.newKeySet();

    final Queue<Path> listed = new ConcurrentLinkedQueue<>();
    final Queue<Path> attributesRead = new ConcurrentLinkedQueue<>();
    final Queue<Path> readabilityChecked = new ConcurrentLinkedQueue<>();
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
            DirectoryStream<Path> stream = super.newDirectoryStream(dir);
            return listingsFailingAfterFirstEntry.contains(dir) ? failingAfterFirstEntry(stream) : stream;
        } finally {
            activeListings.decrementAndGet();
        }
    }

    @Override
    BasicFileAttributes readAttributes(Path path) throws IOException {
        attributesRead.add(path);
        if (path.getParent() != null) {
            sleep(entryAttributeDelays.get(path.getParent()));
        }
        IOException failure = attributeFailures.get(path);
        if (failure != null) {
            throw failure;
        }
        return super.readAttributes(path);
    }

    @Override
    boolean isReadable(Path file) {
        readabilityChecked.add(file);
        return !unreadable.contains(file) && super.isReadable(file);
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

    private static DirectoryStream<Path> failingAfterFirstEntry(DirectoryStream<Path> stream) {
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                Iterator<Path> entries = stream.iterator();
                return new Iterator<>() {
                    private boolean first = true;

                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public Path next() {
                        if (first && entries.hasNext()) {
                            first = false;
                            return entries.next();
                        }
                        throw new DirectoryIteratorException(new IOException("Connection reset by peer"));
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
