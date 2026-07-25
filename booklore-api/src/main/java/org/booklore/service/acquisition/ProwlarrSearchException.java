package org.booklore.service.acquisition;

/**
 * Raised when a Prowlarr search cannot be completed.
 *
 * The client used to swallow every failure into an empty result list, which
 * made a rejected request indistinguishable from a genuine "no releases
 * found" — the wanted-book search button appeared to do nothing at all.
 * Automatic search catches this per book and moves on; the manual path lets it
 * surface so the caller learns why.
 */
public class ProwlarrSearchException extends RuntimeException {

    public ProwlarrSearchException(String message) {
        super(message);
    }
}
