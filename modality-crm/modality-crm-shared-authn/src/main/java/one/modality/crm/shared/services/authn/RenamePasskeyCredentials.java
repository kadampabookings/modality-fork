package one.modality.crm.shared.services.authn;

/**
 * Renames one passkey of the logged-in account.
 *
 * <p>Sent on the {@code updateCredentials} path. Like {@link RemovePasskeyCredentials}, the id is
 * only honoured when the row belongs to the caller's own account.
 *
 * @param passkeyId the id of the credential row to rename, as listed by {@link ListPasskeysCredentials}
 * @param label     the new display name (≤ 64 chars)
 *
 * @author Claude Code
 */
public record RenamePasskeyCredentials(Object passkeyId, String label) {
}
