package one.modality.crm.shared.services.authn;

/**
 * Removes one passkey from the logged-in account.
 *
 * <p>Sent on the {@code updateCredentials} path. {@code passkeyId} is treated as a request, not an
 * instruction: the server deletes the row only when it belongs to the caller's own account
 * (ownership is part of the DELETE's WHERE clause), so a valid id alone is never sufficient.
 *
 * @param passkeyId the id of the credential row to remove, as listed by {@link ListPasskeysCredentials}
 *
 * @author Claude Code
 */
public record RemovePasskeyCredentials(Object passkeyId) {
}
