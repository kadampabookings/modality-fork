package one.modality.crm.shared.services.authn;

/**
 * Completes a passkey (WebAuthn) login ceremony started by {@link StartPasskeyAssertionCredentials}.
 *
 * <p>Sent anonymously on the {@code authenticate} path. The server verifies the assertion —
 * challenge (single-use), origin against its configured allowlist, rpId hash, user verification,
 * and the signature against the stored public key — then signs the session in exactly as a
 * password login would. All fields are the authenticator's response, base64url without padding.
 *
 * @param credentialId      the credential id returned by the authenticator
 * @param authenticatorData the authenticator data bytes
 * @param clientDataJSON    the client data JSON bytes (carries the browser-verified origin)
 * @param signature         the assertion signature
 * @param userHandle        the user handle stored at registration, may be null on some authenticators
 *
 * @author Claude Code
 */
public record AuthenticateWithPasskeyCredentials(String credentialId, String authenticatorData, String clientDataJSON, String signature, String userHandle) {
}
