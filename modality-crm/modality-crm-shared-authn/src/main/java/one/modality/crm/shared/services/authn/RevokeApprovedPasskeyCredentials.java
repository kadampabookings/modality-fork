package one.modality.crm.shared.services.authn;

/**
 * Withdraws a passkey, refusing it for back-office use from now on.
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator (re-checked server-side on
 * every call; never on their own account). The row is set REJECTED and the decision recorded, as
 * {@link RejectPasskeyCredentials} does for one at the queue — kept, not deleted, so its owner sees
 * the decision rather than a passkey that silently vanished, and cannot remove the row themselves.
 *
 * <p>Closes the gap that approval alone left: until now a decision could be made once, at the queue,
 * and never revisited — a lost or shared authenticator stayed approved for as long as the row
 * existed, with no administrator path to take it back.
 *
 * <p><b>The name is narrower than the behaviour, and stays for wire compatibility.</b> Any row that
 * is not already REJECTED is withdrawn, PENDING included — because while the back-office approval
 * switch is off a PENDING passkey opens a back-office login exactly as an approved one does, so
 * clearing every usable factor from an account (what rescuing a locked-out member of staff means)
 * has to reach those rows too. An already-REJECTED row has nothing left to withdraw and answers with
 * the generic management error.
 *
 * @param passkeyId the id of the credential row to revoke, as listed by
 *                  {@link ListAccountPasskeysCredentials} or {@link ListPendingPasskeysCredentials}
 * @param note      why it was withdrawn (≤ 256 chars) — the record of the decision
 *
 * @author Claude Code
 */
public record RevokeApprovedPasskeyCredentials(Object passkeyId, String note) {
}
