package one.modality.crm.shared.services.authn;

/**
 * Withdraws an already-approved passkey, refusing it for back-office use from now on.
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator (re-checked server-side on
 * every call; never on their own account). The row is set REJECTED and the decision recorded, as
 * {@link RejectPasskeyCredentials} does for one that was never approved — kept, not deleted, so its
 * owner sees the decision rather than a passkey that silently vanished, and cannot remove the row
 * themselves.
 *
 * <p>Closes the gap that approval alone left: until now a decision could be made once, at the queue,
 * and never revisited — a lost or shared authenticator stayed approved for as long as the row
 * existed, with no administrator path to take it back.
 *
 * @param passkeyId the id of the credential row to revoke, as listed by {@link ListPendingPasskeysCredentials}
 * @param note      why it was withdrawn (≤ 256 chars) — the record of the decision
 *
 * @author Claude Code
 */
public record RevokeApprovedPasskeyCredentials(Object passkeyId, String note) {
}
