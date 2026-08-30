package one.modality.crm.shared.services.authn;

/**
 * Asks the server to start a passkey (WebAuthn) registration ceremony for the logged-in account.
 *
 * <p>Sent on the {@code updateCredentials} path so the caller's principal is available server-side —
 * only an authenticated, non-support-view session may plant a credential on its own account. The
 * reply is a JSON string shaped like {@code PublicKeyCredentialCreationOptions} (challenge, rp,
 * user handle, excludeCredentials…) for the browser's {@code navigator.credentials.create()} call.
 *
 * <p>Carries no fields on purpose: the server chooses every parameter of the ceremony (challenge,
 * user handle, algorithms, resident-key and user-verification policies), never the client.
 *
 * @author Claude Code
 */
public record StartPasskeyRegistrationCredentials() {
}
