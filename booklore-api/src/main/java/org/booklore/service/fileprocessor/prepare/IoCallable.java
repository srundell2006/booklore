package org.booklore.service.fileprocessor.prepare;

/** A step of a file's preparation, which may fail with any exception. */
@FunctionalInterface
public interface IoCallable<T> {

    T call() throws Exception;
}
