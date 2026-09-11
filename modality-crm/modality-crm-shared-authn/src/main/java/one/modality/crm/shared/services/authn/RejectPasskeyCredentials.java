package one.modality.crm.shared.services.authn;

/**
 * Rejects a pending passkey, refusing it for back-office use and recording the decision.
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator (re-checked
 * server-side on every call; never for the approver's own account). A rejected credential is
 * kept, not deleted: its owner sees the decision in their profile instead of a passkey that
 * silently vanished, and cannot remove the row themselves, so the rejection stays on record.
 *
 * @param passkeyId the id of the credential row, as listed by {@link ListPendingPasskeysCredentials}
 *
 * @author Claude Code
 */
public record RejectPasskeyCredentials(Object passkeyId) {
}
