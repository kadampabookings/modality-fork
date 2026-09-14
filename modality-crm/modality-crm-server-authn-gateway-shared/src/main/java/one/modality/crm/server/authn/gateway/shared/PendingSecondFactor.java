package one.modality.crm.server.authn.gateway.shared;

import java.util.List;

/**
 * One login that has proved its password and is waiting for its second factor.
 *
 * <p>It lives in {@link PendingSecondFactorStore}, keyed by the client's {@code runId}, and
 * deliberately NOT in the session: nothing about the session changes until the second step mints, so
 * there is no half-established identity for the client's next message to undo (the React client
 * re-sends its whole state, {@code userId} included, and a logged-out client sends the logout id —
 * a session holding a user against a client claiming logged-out is read as a logout and pushed as
 * one).
 *
 * @param personId        the person the password resolved to — what the session will be minted for
 * @param accountId       the frontend account, the subject of every enrolment and verification lookup
 * @param backoffice      whether this was a back-office authentication (captured before the first async hop)
 * @param methods         the factor codes this account may answer with, from {@link SecondFactorVerifiers}
 * @param expiresAtMillis wall-clock expiry; an entry past it is treated as absent
 * @param attemptsLeft    how many verification attempts remain on this {@code runId}
 *
 * @author Claude Code
 */
public record PendingSecondFactor(Object personId, Object accountId, boolean backoffice, List<String> methods,
                                  long expiresAtMillis, int attemptsLeft) {

    boolean isExpired(long nowMillis) {
        return nowMillis > expiresAtMillis;
    }

    /** The same pending login with one attempt spent — see {@link PendingSecondFactorStore#recordAttempt}. */
    PendingSecondFactor withOneAttemptLess() {
        return new PendingSecondFactor(personId, accountId, backoffice, methods, expiresAtMillis, attemptsLeft - 1);
    }
}
