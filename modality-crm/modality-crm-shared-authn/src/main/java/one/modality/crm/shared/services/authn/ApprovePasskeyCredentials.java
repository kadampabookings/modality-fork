package one.modality.crm.shared.services.authn;

/**
 * Approves a pending passkey for back-office use.
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator (re-checked
 * server-side on every call). Only a PENDING credential can be approved, so a second approver
 * racing the first finds nothing to do rather than overwriting the decision.
 *
 * @param passkeyId the id of the credential row, as listed by {@link ListPendingPasskeysCredentials}
 *
 * @author Claude Code
 */
public record ApprovePasskeyCredentials(Object passkeyId) {
}
