package one.modality.crm.shared.services.authn;

/**
 * Asks the server to start a passkey (WebAuthn) registration ceremony for the logged-in account.
 *
 * <p>Sent on the {@code updateCredentials} path so the caller's principal is available server-side —
 * only an authenticated, non-support-view session may plant a credential on its own account. The
 * reply is a JSON string shaped like {@code PublicKeyCredentialCreationOptions} (challenge, rp,
 * user handle, excludeCredentials…) for the browser's {@code navigator.credentials.create()} call.
 *
 * <p>Carries nothing about the ceremony on purpose: the server chooses every parameter of it (challenge,
 * user handle, algorithms, resident-key and user-verification policies), never the client.
 *
 * <p>It does carry the account's current password, because a passkey is lasting access: a session that
 * could add one would turn a taken laptop into an account the thief can sign back into. The server requires
 * the password, or a recovery minutes old (then this is null), before it starts anything.
 *
 * @param currentPassword the account's current password as typed, or null to rely on a recent recovery
 * @author Claude Code
 */
public record StartPasskeyRegistrationCredentials(String currentPassword) {

    /** Redacted: a record's generated toString() would print the password into any message or log it reaches. */
    @Override
    public String toString() {
        return "StartPasskeyRegistrationCredentials[currentPassword=" + (currentPassword == null ? "null" : "***") + "]";
    }
}
