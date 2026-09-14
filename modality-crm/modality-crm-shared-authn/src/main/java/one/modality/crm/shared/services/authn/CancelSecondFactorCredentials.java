package one.modality.crm.shared.services.authn;

/**
 * Abandons a back-office login that is waiting for its second factor.
 *
 * <p>Sent on the {@code authenticate} path, anonymously: the server drops the pending entry held for
 * this {@code runId} and answers nothing. The password step is then simply undone — no session was
 * ever created, nothing was pushed — and the user is back at the login form.
 *
 * <p>Carries no fields on purpose: the entry is keyed by the {@code runId} the connection already
 * carries, so the only thing a caller can cancel is their own tab's pending login. Letting it die of
 * its own five-minute expiry would work too; cancelling frees the slot at once and makes "I clicked
 * back" mean what it looks like.
 *
 * @author Claude Code
 */
public record CancelSecondFactorCredentials() {
}
