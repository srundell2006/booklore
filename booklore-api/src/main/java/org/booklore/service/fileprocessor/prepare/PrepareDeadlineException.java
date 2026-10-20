package org.booklore.service.fileprocessor.prepare;

/**
 * Thrown when a file's deadline passes while it waits for a render permit or heap. Like
 * {@link PrepareAbandonedException} it is let through by code that otherwise carries on without a cover.
 */
public class PrepareDeadlineException extends RuntimeException {

    public PrepareDeadlineException(String message) {
        super(message);
    }
}
