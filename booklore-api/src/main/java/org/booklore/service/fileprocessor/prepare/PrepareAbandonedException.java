package org.booklore.service.fileprocessor.prepare;

/**
 * Thrown inside a file's preparation once it is no longer wanted. Code that catches exceptions to carry on without a
 * cover or metadata lets this one through, so the preparation ends.
 */
public class PrepareAbandonedException extends RuntimeException {

    public PrepareAbandonedException(String message) {
        super(message);
    }
}
