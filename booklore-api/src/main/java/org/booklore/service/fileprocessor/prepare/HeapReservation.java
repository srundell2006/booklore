package org.booklore.service.fileprocessor.prepare;

/** Heap reserved from the import heap budget, given back on close. */
public interface HeapReservation extends AutoCloseable {

    @Override
    void close();
}
