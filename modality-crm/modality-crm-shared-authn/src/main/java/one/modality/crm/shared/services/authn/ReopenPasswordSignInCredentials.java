package one.modality.crm.shared.services.authn;

/**
 * Lifts "Stop my password working": password sign-in and recovery work again. Nothing comes back — the password was
 * wiped when it was closed — so the owner sets a new one through "Forgot password", which proves the mailbox.
 *
 * <p>Two senders, on the {@code updateCredentials} path, never inside a support view:
 * <ul>
 *   <li>the owner, with no account id. Reopening re-opens recovery, so it needs a proof beyond the session: a passkey
 *       sign-in in this tab minutes ago (CredentialChangeProof), or it is refused as unproved — otherwise a session
 *       taken from the owner, with their mailbox, could undo the control.</li>
 *   <li>a super administrator, naming the account and noting the out-of-band check they made — the rescue for an
 *       owner who lost every passkey. Membership is re-checked server-side on every call, and the note is required
 *       and kept with the rescue (second_factor_reset), like every other rescue's.</li>
 * </ul>
 * The reply is whether a restriction was actually lifted.
 *
 * @param accountId the account to reopen, for a super administrator's rescue; null for the caller's own
 * @param note      the super administrator's record of the check they made; ignored for the owner's own reopen
 *
 * @author Claude Code
 */
public record ReopenPasswordSignInCredentials(Object accountId, String note) {
}
